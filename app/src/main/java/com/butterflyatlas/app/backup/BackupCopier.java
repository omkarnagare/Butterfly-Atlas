package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.DatabaseBusy;
import com.butterflyatlas.app.exports.ExportCleanup;
import com.butterflyatlas.app.exports.ExportCopier;
import com.butterflyatlas.app.exports.ExportErrors;
import com.butterflyatlas.app.exports.ExportJobs;
import com.butterflyatlas.app.exports.ExportStore;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The copy of one backup (design §6, contract §4), run by {@link ExportJobs} like an export's: for Everything, first each
 * live photo in id order as an exact copy in the library's own structure ({@code .thumbs} and {@code .deleted} are never
 * live photos), written to {@code <name>.part} and put in place the way an export's are ({@link ExportCopier#place}), its
 * size compared with the original's; then, last, one consistent copy of the database ({@link DatabaseCopy}) so it matches
 * the photo tree as it ended, cleaned of this backup's own record, put in place without replacing anything and read back. Progress is saved in batches with the id of the last photo settled, so a resume goes on
 * after it; a full drive, a folder that is gone, a database copy that cannot be read back or five failed writes in a row
 * pause the backup, without blaming the photos (they stay to copy). The backup is done only when every step is verified.
 */
final class BackupCopier implements ExportJobs.Work {

    private static final Logger LOG = LoggerFactory.getLogger(BackupCopier.class);

    static final String DATABASE = Database.FILE_NAME;
    static final int SAVE_EVERY = 50;
    static final Duration SAVE_EVERY_TIME = Duration.ofSeconds(1);
    static final int WRITE_ERRORS = 5;
    private static final int PAGE = 500;

    static final String REMOVED = "The photo was removed while it was being copied.";
    static final String UNREADABLE = "The photo could not be read.";
    static final String UNWRITTEN = "The copy could not be written.";
    static final String CHANGED = "The copy in the backup folder was changed afterwards, so it was left as it is.";
    static final String NOT_A_FOLDER = "A folder in the backup folder is a link or a file, not a folder, so the photo was not copied.";
    static final String WRONG_SIZE = "The copy was not the same size as the original, so it was deleted.";

    static final String DRIVE_GONE_NOTICE = "The backup folder can't be reached. Reconnect the drive, then resume.";
    static final String WRITE_ERRORS_NOTICE = "Several photos in a row could not be written. Check the drive, then resume.";
    static final String EXISTING_DATABASE_NOTICE = "A file named atlas.db is already in the backup folder, and Butterfly Atlas will"
            + " not replace it. Move or rename it, then resume.";
    static final String DATABASE_NOTICE = "The copy of the database could not be made. Check the drive, then resume.";

    /** How one photo went: an outcome to record, or a write that failed (not the photo's fault). */
    private record Copied(BackupStore.Outcome outcome, boolean writeFailed) {
    }

    /** Opens a {@code .part} file for writing (tests make writes fail). */
    @FunctionalInterface
    interface Opener {
        OutputStream open(Path part) throws IOException;
    }

    private final Supplier<Library> library;
    private final BackupStore store;
    private final ToLongFunction<Path> usableSpace;
    volatile ExportCopier.Linker linker = Files::createLink;
    volatile ExportCopier.Deleter partDeleter = Files::delete;
    volatile Opener opener = part -> Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    /** Runs once a photo's {@code .part} is written, with it (tests damage it there). */
    volatile Consumer<Path> afterWrite = part -> { };
    /** Runs once the database's copy is in place, before it is read back, with it (tests damage it there). */
    volatile Consumer<Path> afterDatabase = copy -> { };
    /** Runs before each photo is copied, with its id (tests change the library or the drive there). */
    volatile Consumer<Long> beforePhoto = id -> { };
    /** Progress is saved after this many photos (tests change it). */
    volatile int saveEvery = SAVE_EVERY;
    /** The clock of the timed saves (tests stop it). */
    volatile LongSupplier nanos = System::nanoTime;

    BackupCopier(Supplier<Library> library, BackupStore store, ToLongFunction<Path> usableSpace) {
        this.library = library;
        this.store = store;
        this.usableSpace = usableSpace;
    }

    @Override
    public void copy(long runId, Supplier<ExportJobs.Stop> stop) {
        BackupStore.Run run = store.run(runId);
        if (!BackupStore.RUNNING.equals(run.state())) {
            return;
        }
        Path folder = run.folderPath();
        if (!ExportCleanup.exportFolder(folder)) {
            store.pause(runId, "drive", DRIVE_GONE_NOTICE);
            return;
        }
        Library lib = library.get();
        if (!repair(runId, lib, folder)) { // a resume trusts nothing it recorded: every file is looked at again
            return;
        }
        // the photos first, the database last: its copy then matches the photo tree as it ended (a resume across the two
        // goes on after the last photo settled, and makes the database's copy only once the photos are all done)
        if ("everything".equals(run.what()) && !copyPhotos(run, lib, folder, stop)) {
            return;
        }
        if (!store.hasFile(runId, DATABASE)) {
            if (stopped(runId, stop, List.of(), 0)) {
                return;
            }
            if (!copyDatabase(runId, lib, folder)) {
                return;
            }
        }
        // and once more before the backup can be done: never with a recorded file missing or short
        if (!repair(runId, lib, folder)) {
            return;
        }
        if (!store.hasFile(runId, DATABASE) && !copyDatabase(runId, lib, folder)) {
            return;
        }
        store.finish(runId);
    }

    /**
     * Looks at every file the backup recorded as written: one that is gone is copied again (a photo) or made again (the
     * database); one whose size is not the recorded one is not trusted and not replaced: a photo is failed with its reason, the
     * database copy is forgotten (it is then refused in words, as any file already there); the database's copy is read back
     * again. False when the backup was paused (a write failed).
     */
    private boolean repair(long runId, Library lib, Path folder) {
        List<ExportStore.Written> missing = new ArrayList<>();
        List<ExportStore.Written> changed = new ArrayList<>();
        for (ExportStore.Written file : store.writtenFiles(runId)) {
            Path path = ExportCopier.resolve(folder, file.targetRel());
            long size;
            boolean gone = false;
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                size = attrs.isRegularFile() ? attrs.size() : -1;
            } catch (NoSuchFileException e) {
                size = -1;
                gone = true;
            } catch (IOException e) {
                size = -1;
            }
            boolean database = DATABASE.equals(file.targetRel());
            if (size == file.bytes()) {
                if (database) {
                    try {
                        DatabaseCopy.verify(path);
                    } catch (DatabaseCopy.CopyException e) {
                        LOG.warn("The recorded copy of the database in {} cannot be read back; it is made again", folder, e);
                        deleteQuietly(path); // the size is the recorded one: it is the copy this backup made
                        store.dropFile(runId, DATABASE);
                    }
                }
                continue;
            }
            LOG.info("Backup {}: {} is {} in the backup folder", runId, file.targetRel(), gone ? "missing" : "not as it was written");
            if (database) {
                store.dropFile(runId, DATABASE);
            } else if (gone) {
                missing.add(file);
            } else {
                changed.add(file);
            }
        }
        if (missing.isEmpty() && changed.isEmpty()) {
            return true;
        }
        store.unrecord(runId, missing);
        store.unrecord(runId, changed);
        List<BackupStore.Outcome> outcomes = new ArrayList<>();
        for (ExportStore.Written file : changed) {
            outcomes.add(new BackupStore.Outcome(0, file.targetRel(), BackupStore.FAILED, CHANGED, file.bytes(), 0, 0));
        }
        for (ExportStore.Written file : missing) {
            BackupStore.Photo photo = store.photoByRel(file.targetRel()).orElse(null);
            if (photo == null) {
                outcomes.add(new BackupStore.Outcome(0, file.targetRel(), BackupStore.SKIPPED, REMOVED, file.bytes(), 0, 0));
                continue;
            }
            Copied copied = copyOne(lib, folder, photo);
            if (copied.writeFailed()) {
                save(runId, outcomes, 0);
                return pauseForWrite(runId, folder, photo.bytes(), WRITE_ERRORS_NOTICE);
            }
            outcomes.add(copied.outcome());
        }
        save(runId, outcomes, 0);
        return true;
    }

    /** Honours a stop asked for: saves what is settled, pauses (a pause or cancel) or leaves it running (closing). */
    private boolean stopped(long runId, Supplier<ExportJobs.Stop> stop, List<BackupStore.Outcome> batch, long settled) {
        ExportJobs.Stop why = stop.get();
        if (why == ExportJobs.Stop.NONE) {
            return false;
        }
        save(runId, batch, settled);
        if (why == ExportJobs.Stop.PAUSE || why == ExportJobs.Stop.CANCEL) {
            // a cancel is recorded paused too: its own request records the cancel once this returns
            store.pause(runId, "user", null);
        }
        return true; // closing: left running, so the next start of the app marks it paused (closed)
    }

    /** The database's copy, in place and read back. False when the backup was paused instead. */
    private boolean copyDatabase(long runId, Library lib, Path folder) {
        Path target = folder.resolve(DATABASE);
        Path part = ExportCopier.part(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // never replaced, never deleted blindly: this backup has not recorded it, so it is not known to be ours
            LOG.warn("{} is already in {}; the backup will not replace it", DATABASE, folder);
            store.pause(runId, ErrorCodes.WRITE_ERRORS, EXISTING_DATABASE_NOTICE);
            return false;
        }
        try {
            Files.deleteIfExists(part); // a leftover of a copy the app's closing cut short: this folder is this backup's
            DatabaseCopy.copy(lib.database(), part);
            DatabaseCopy.sanitise(part, runId);
            ExportCopier.place(part, target, linker, partDeleter);
        } catch (SQLException | IOException e) {
            LOG.warn("Could not copy the database into {}", folder, e);
            deleteQuietly(part);
            deleteQuietly(target);
            return pauseForWrite(runId, folder, lib.database().file().toFile().length(), DATABASE_NOTICE);
        }
        try {
            afterDatabase.accept(target);
            DatabaseCopy.verify(target);
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            store.addFile(runId, DATABASE, attrs.size(), attrs.lastModifiedTime().toMillis());
            return true;
        } catch (DatabaseCopy.CopyException | IOException e) {
            LOG.warn("The copy of the database in {} could not be read back", folder, e);
            deleteQuietly(target);
            store.pause(runId, ErrorCodes.WRITE_ERRORS, e.getMessage() + " Check the drive, then resume.");
            return false;
        }
    }

    /** Pauses for what a failed write says: the folder gone, the drive full, or a write that fails for another reason. */
    private boolean pauseForWrite(long runId, Path folder, long needed, String otherwise) {
        if (!ExportCleanup.exportFolder(folder)) {
            store.pause(runId, "drive", DRIVE_GONE_NOTICE);
        } else if (usableSpace.applyAsLong(folder) < needed) {
            store.pause(runId, "drive", ExportErrors.driveFull(needed - usableSpace.applyAsLong(folder)));
        } else {
            store.pause(runId, ErrorCodes.WRITE_ERRORS, otherwise);
        }
        return false;
    }

    /** Every live photo after the cursor. False when the backup was stopped or paused before the end. */
    private boolean copyPhotos(BackupStore.Run run, Library lib, Path folder, Supplier<ExportJobs.Stop> stop) {
        long runId = run.id();
        List<BackupStore.Outcome> batch = new ArrayList<>();
        List<BackupStore.Outcome> held = new ArrayList<>(); // failed writes not yet blamed on their photos
        long heldBytes = 0;
        long batchBytes = 0;
        long settled = run.lastImageId();
        long cursor = settled;
        long saved = nanos.getAsLong();
        try {
            while (true) {
                List<BackupStore.Photo> photos = store.photos(cursor, PAGE);
                if (photos.isEmpty()) {
                    break;
                }
                for (BackupStore.Photo photo : photos) {
                    if (stopped(runId, stop, batch, settled)) {
                        batch.clear();
                        return false;
                    }
                    cursor = photo.id();
                    beforePhoto.accept(photo.id());
                    if (!ExportCleanup.exportFolder(folder)) {
                        return pausing(runId, batch, settled, "drive", DRIVE_GONE_NOTICE);
                    }
                    Copied copied = copyOne(lib, folder, photo);
                    if (copied.writeFailed()) {
                        if (!ExportCleanup.exportFolder(folder)) {
                            return pausing(runId, batch, settled, "drive", DRIVE_GONE_NOTICE);
                        }
                        long needed = Math.max(0, run.bytesTotal() - run.bytesDone() - batchBytes);
                        long free = usableSpace.applyAsLong(folder);
                        if (free < Math.min(needed, photo.bytes())) {
                            return pausing(runId, batch, settled, "drive", ExportErrors.driveFull(photo.bytes() - free));
                        }
                        held.add(copied.outcome());
                        heldBytes += photo.bytes();
                        if (held.size() >= WRITE_ERRORS) {
                            return pausing(runId, batch, settled, ErrorCodes.WRITE_ERRORS, WRITE_ERRORS_NOTICE);
                        }
                        continue;
                    }
                    batch.addAll(held); // a photo after them was written: those were the photos' own failures
                    batchBytes += heldBytes;
                    held.clear();
                    heldBytes = 0;
                    batch.add(copied.outcome());
                    batchBytes += photo.bytes();
                    settled = photo.id();
                    long now = nanos.getAsLong();
                    if (batch.size() >= saveEvery || now - saved >= SAVE_EVERY_TIME.toNanos()) {
                        save(runId, batch, settled);
                        batch.clear();
                        batchBytes = 0;
                        saved = now;
                    }
                }
            }
            batch.addAll(held);
            if (!held.isEmpty()) {
                settled = cursor;
            }
            save(runId, batch, settled);
            batch.clear();
            return true;
        } catch (RuntimeException | Error e) {
            try {
                save(runId, batch, settled); // what was copied before the error is recorded; the rest stays to copy
            } catch (RuntimeException | Error saving) {
                e.addSuppressed(saving);
            }
            throw e;
        }
    }

    /**
     * Saves the photos settled so far: a save the database stayed busy for (another window's long write) is tried again,
     * {@link DatabaseBusy#RETRIES} more times (audit A16); then it fails as any write does (the backup pauses).
     */
    private void save(long runId, List<BackupStore.Outcome> outcomes, long settled) {
        List<BackupStore.Outcome> saving = List.copyOf(outcomes);
        DatabaseBusy.retry("saving a backup's progress", () -> {
            store.save(runId, saving, settled);
            return null;
        });
    }

    private boolean pausing(long runId, List<BackupStore.Outcome> batch, long settled, String why, String notice) {
        save(runId, batch, settled);
        batch.clear();
        store.pause(runId, why, notice);
        LOG.info("Backup {} paused: {}", runId, why);
        return false;
    }

    /**
     * One photo from its live file. A file that went while it was read may have been moved by the library (a rename, a fix of
     * its species), so its live path is read once more and the copy tried again from there.
     */
    private Copied copyOne(Library lib, Path folder, BackupStore.Photo photo) {
        Copied copied = copyFrom(lib, folder, photo, photo.rel());
        if (BackupStore.SKIPPED.equals(copied.outcome().state()) && REMOVED.equals(copied.outcome().reason())) {
            String now = livePath(lib, photo.id());
            if (now != null && !now.equals(photo.rel())) {
                return copyFrom(lib, folder, photo, now);
            }
        }
        return copied;
    }

    private Copied copyFrom(Library lib, Path folder, BackupStore.Photo photo, String rel) {
        Path source = ExportCopier.resolve(lib.folder(), rel);
        long size;
        try {
            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return outcome(photo, rel, BackupStore.SKIPPED, REMOVED);
            }
            size = attrs.size();
        } catch (NoSuchFileException e) {
            return outcome(photo, rel, BackupStore.SKIPPED, REMOVED);
        } catch (IOException e) {
            return outcome(photo, rel, BackupStore.FAILED, UNREADABLE);
        }
        Path target = ExportCopier.resolve(folder, rel);
        Path part = ExportCopier.part(target);
        long written;
        try {
            if (!folders(folder, target.getParent())) {
                return outcome(photo, rel, BackupStore.FAILED, NOT_A_FOLDER);
            }
            deleteQuietly(part); // a leftover of a copy the app's closing cut short
            try (OutputStream out = opener.open(part)) {
                written = copy(source, out);
            }
            afterWrite.accept(part);
        } catch (ReadFailure e) {
            deleteQuietly(part);
            return e.getCause() instanceof NoSuchFileException ? outcome(photo, rel, BackupStore.SKIPPED, REMOVED)
                    : outcome(photo, rel, BackupStore.FAILED, UNREADABLE);
        } catch (IOException e) {
            LOG.debug("Could not write {}", part, e);
            deleteQuietly(part);
            return new Copied(new BackupStore.Outcome(photo.id(), rel, BackupStore.FAILED, UNWRITTEN, photo.bytes(), 0, 0), true);
        }
        try {
            if (written != size || Files.size(part) != size || Files.size(source) != size) {
                deleteQuietly(part); // the photo changed while it was read, or the drive wrote less than it was given
                return outcome(photo, rel, BackupStore.FAILED, WRONG_SIZE);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                // only this backup writes in its folder: a file already there is a copy the app's closing cut short, kept
                // when it holds the same bytes, never replaced
                boolean same = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(part, target) == -1;
                deleteQuietly(part);
                return same ? done(photo, rel, target, size) : outcome(photo, rel, BackupStore.FAILED, UNWRITTEN);
            }
            ExportCopier.place(part, target, linker, partDeleter);
            if (Files.size(target) != size) {
                deleteQuietly(target);
                return outcome(photo, rel, BackupStore.FAILED, WRONG_SIZE);
            }
        } catch (FileAlreadyExistsException e) {
            deleteQuietly(part); // made in the gap: kept, and this photo's own failure
            return outcome(photo, rel, BackupStore.FAILED, UNWRITTEN);
        } catch (NoSuchFileException e) {
            deleteQuietly(part);
            return outcome(photo, rel, BackupStore.SKIPPED, REMOVED);
        } catch (IOException e) {
            LOG.debug("Could not move {} into place", part, e);
            deleteQuietly(part);
            return new Copied(new BackupStore.Outcome(photo.id(), rel, BackupStore.FAILED, UNWRITTEN, photo.bytes(), 0, 0), true);
        }
        return done(photo, rel, target, size);
    }

    /**
     * Makes the folders from the backup folder down to {@code parent}, one level at a time, never through a link: false when
     * something that is not a real folder (a file, a link) is in the way.
     */
    private static boolean folders(Path folder, Path parent) throws IOException {
        if (parent.equals(folder)) {
            return true;
        }
        Path at = folder;
        for (Path name : folder.relativize(parent)) {
            at = at.resolve(name);
            try {
                Files.createDirectory(at);
            } catch (FileAlreadyExistsException e) {
                if (!realFolder(at)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A folder read without following links: a directory that is not a link, and (a Windows junction) is its own real path. */
    private static boolean realFolder(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink()) {
                return false;
            }
            return !attrs.isOther()
                    || path.toRealPath().equals(path.getParent().toRealPath().resolve(path.getFileName()));
        } catch (IOException e) {
            return false;
        }
    }

    /** Done: with the size and time of the file at the target, so Remove deletes it only while it is unchanged. */
    private static Copied done(BackupStore.Photo photo, String rel, Path target, long size) {
        long mtime = 0;
        try {
            mtime = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).lastModifiedTime()
                    .toMillis();
        } catch (IOException e) {
            LOG.debug("Could not read the time of {}", target, e);
        }
        return new Copied(new BackupStore.Outcome(photo.id(), rel, BackupStore.ITEM_DONE, null, photo.bytes(), size, mtime),
                false);
    }

    private static Copied outcome(BackupStore.Photo photo, String rel, String state, String reason) {
        return new Copied(new BackupStore.Outcome(photo.id(), rel, state, reason, photo.bytes(), 0, 0), false);
    }

    /** The photo's live {@code rel_path}; null when it is deleted (Recently deleted, or gone). */
    private String livePath(Library lib, long imageId) {
        return lib.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM image WHERE id = ? AND deleted_at IS NULL")) {
                ps.setLong(1, imageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** A read of the original that failed (not the write). */
    private static final class ReadFailure extends IOException {
        ReadFailure(IOException cause) {
            super(cause);
        }
    }

    /** Streams the original into {@code out} unchanged; returns the bytes written. */
    private static long copy(Path source, OutputStream out) throws IOException {
        long total = 0;
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = open(source)) {
            while (true) {
                int n;
                try {
                    n = in.read(buffer);
                } catch (IOException e) {
                    throw new ReadFailure(e);
                }
                if (n < 0) {
                    return total;
                }
                out.write(buffer, 0, n);
                total += n;
            }
        }
    }

    private static InputStream open(Path source) throws ReadFailure {
        try {
            return Files.newInputStream(source);
        } catch (IOException e) {
            throw new ReadFailure(e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
        }
    }
}
