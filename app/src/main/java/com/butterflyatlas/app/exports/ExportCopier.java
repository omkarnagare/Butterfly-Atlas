package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.imaging.raw.RawPreviews;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The copy of one export (4b contract §3): per photo, its current file (its live {@code rel_path}) read through NIO
 * only, written to {@code <target>.part} with {@code CREATE_NEW}, its location removed while writing when chosen, the
 * {@code .part} read back (still a location: deleted, failed), then put at the target without ever replacing a file
 * ({@link #place}); a target already there is kept, and the photo is done only when it holds the same bytes (then not
 * known to be this export's: Remove keeps it). Progress is saved in batches and when it stops. A full drive, an export
 * folder that is gone (or is no longer a real folder) or five failed writes in a row pause it, without blaming the photos
 * (they stay to copy).
 */
public final class ExportCopier implements ExportJobs.Work {

    private static final Logger LOG = LoggerFactory.getLogger(ExportCopier.class);

    /** Progress is saved after this many photos, or this long, whichever comes first. */
    static final int SAVE_EVERY = 50;
    static final Duration SAVE_EVERY_TIME = Duration.ofSeconds(1);
    /** Failed writes in a row that pause the export. */
    static final int WRITE_ERRORS = 5;

    static final String DELETED = "The photo was deleted after the export started.";
    static final String MISSING = "The photo's file is missing.";
    static final String UNREADABLE = "The photo could not be read.";
    static final String UNWRITTEN = "The copy could not be written.";
    static final String NOT_STRIPPED = "Its location could not be removed.";
    /** A photo not marked exact at the start whose file is RAW or HEIC now (Phase 7b audit 23). */
    static final String NOW_EXACT = "This photo's file is now RAW or HEIC, which Remove location can't change. Export it"
            + " again and tick the box to include it.";
    static final String STILL_LOCATED = "It still had a location after removing it, so the copy was deleted.";

    static final String DRIVE_GONE_NOTICE = "The export folder can't be reached. Reconnect the drive, then resume.";
    static final String WRITE_ERRORS_NOTICE = "Several photos in a row could not be written. Check the drive, then resume.";

    private static final boolean WINDOWS = FileSystems.getDefault().getSeparator().equals("\\");
    private static final int PAGE = 500;

    /** Makes a hard link at {@code target} to {@code existing} ({@code Files.createLink}; tests make it unsupported). */
    @FunctionalInterface
    public interface Linker {
        void link(Path target, Path existing) throws IOException;
    }

    /** Deletes a file (tests make it fail). */
    @FunctionalInterface
    public interface Deleter {
        void delete(Path file) throws IOException;
    }

    /** Puts a written {@code .part} at its target without ever replacing a file; true when the {@code .part} stayed. */
    @FunctionalInterface
    interface Placer {
        boolean place(Path part, Path target) throws IOException;
    }

    /** Reads a path's attributes without following links (tests make folders read as Windows reparse points). */
    @FunctionalInterface
    interface Attributes {
        BasicFileAttributes read(Path path) throws IOException;
    }

    /** Opens a {@code .part} file for writing (tests make writes fail). */
    @FunctionalInterface
    interface Opener {
        OutputStream open(Path part) throws IOException;
    }

    /** How one photo went: an outcome to record, or a write that failed (not the photo's fault). */
    private record Copied(ExportStore.Outcome outcome, boolean writeFailed) {
    }

    private final Supplier<Library> library;
    private final ExportStore store;
    private final MetadataStripper stripper;
    private final ToLongFunction<Path> usableSpace;
    volatile Opener opener = part -> Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    volatile Linker linker = Files::createLink;
    volatile Deleter partDeleter = Files::delete;
    /**
     * Runs just before a copy or the species list is put in place, with its target (tests make the target appear
     * there).
     */
    volatile Consumer<Path> beforeMove = target -> { };
    /** Runs before each photo is copied (tests change the library or the drive there). */
    volatile Consumer<ExportStore.Item> beforePhoto = item -> { };
    /**
     * Reads the attributes of the folders a copy is written in ({@link #realFolder}): read without following links, as
     * they are; tests make folders read as Windows reparse points (an OneDrive folder, a junction).
     */
    volatile Attributes attributes = path -> Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    /** Progress is saved after this many photos (tests change it). */
    volatile int saveEvery = SAVE_EVERY;
    /** The clock of the timed saves (tests stop it). */
    volatile LongSupplier nanos = System::nanoTime;
    /** Exports with a placed copy whose {@code .part} could not be removed: their done targets are swept too. */
    private final Set<Long> partsLeft = ConcurrentHashMap.newKeySet();

    ExportCopier(Supplier<Library> library, ExportStore store, MetadataStripper stripper, ToLongFunction<Path> usableSpace) {
        this.library = library;
        this.store = store;
        this.stripper = stripper;
        this.usableSpace = usableSpace;
    }

    @Override
    public void copy(long runId, Supplier<ExportJobs.Stop> stop) {
        List<ExportStore.Outcome> batch = new ArrayList<>();
        try {
            copy(runId, stop, batch);
        } catch (RuntimeException | Error e) {
            try {
                store.save(runId, batch); // what was copied before the error is recorded; the rest stays to copy
            } catch (RuntimeException | Error saving) {
                e.addSuppressed(saving);
            }
            throw e;
        }
    }

    private void copy(long runId, Supplier<ExportJobs.Stop> stop, List<ExportStore.Outcome> batch) {
        ExportStore.Run run = store.run(runId);
        if (!ExportStore.RUNNING.equals(run.state())) {
            return;
        }
        Path folder = run.folder();
        if (!ExportCleanup.exportFolder(folder)) {
            store.pause(runId, ErrorCodes.DRIVE_GONE, DRIVE_GONE_NOTICE);
            return;
        }
        prepare(runId, folder);
        List<ExportStore.Outcome> writeFailures = new ArrayList<>();
        long writeFailureBytes = 0;
        long batchBytes = 0; // the sizes at the start of the photos in the batch (still pending in the database)
        long saved = nanos.getAsLong();
        int after = 0;
        while (true) {
            List<ExportStore.Item> items = store.pending(runId, after, PAGE);
            if (items.isEmpty()) {
                break;
            }
            for (ExportStore.Item item : items) {
                ExportJobs.Stop why = stop.get();
                if (why != ExportJobs.Stop.NONE) {
                    store.save(runId, batch); // failed writes not yet blamed stay to copy
                    if (why == ExportJobs.Stop.PAUSE || why == ExportJobs.Stop.CANCEL) {
                        // a cancel is recorded paused too: its own request records the cancel once this returns, and
                        // if that request gave up waiting (503) the export is paused, never running with no copy
                        store.pause(runId, "you", null);
                    }
                    return; // closing: left running, so the next start of the app marks it paused (closing)
                }
                after = item.seq();
                beforePhoto.accept(item);
                if (!ExportCleanup.exportFolder(folder)) {
                    // checked before each photo: its folders are made below the export folder, never the folder itself
                    pause(runId, batch, ErrorCodes.DRIVE_GONE, DRIVE_GONE_NOTICE);
                    return;
                }
                Copied copied = copyOne(run, folder, item);
                if (copied.writeFailed()) {
                    if (!ExportCleanup.exportFolder(folder)) {
                        pause(runId, batch, ErrorCodes.DRIVE_GONE, DRIVE_GONE_NOTICE);
                        return;
                    }
                    long needed = store.pendingBytes(runId) - batchBytes;
                    long free = usableSpace.applyAsLong(folder);
                    if (free < needed) {
                        pause(runId, batch, ErrorCodes.DRIVE_FULL, ExportErrors.driveFull(needed - free));
                        return;
                    }
                    writeFailures.add(copied.outcome());
                    writeFailureBytes += item.bytes();
                    if (writeFailures.size() >= WRITE_ERRORS) {
                        pause(runId, batch, ErrorCodes.WRITE_ERRORS, WRITE_ERRORS_NOTICE);
                        return;
                    }
                    continue;
                }
                batch.addAll(writeFailures); // a photo after them was written: those were the photos' own failures
                batchBytes += writeFailureBytes;
                writeFailures.clear();
                writeFailureBytes = 0;
                batch.add(copied.outcome());
                batchBytes += item.bytes();
                long now = nanos.getAsLong();
                if (batch.size() >= saveEvery || now - saved >= SAVE_EVERY_TIME.toNanos()) {
                    store.save(runId, batch);
                    batch.clear();
                    batchBytes = 0;
                    saved = now;
                }
            }
        }
        batch.addAll(writeFailures);
        store.save(runId, batch);
        if (partsLeft.contains(runId)) {
            prepare(runId, folder); // a placed copy's .part that could not be removed: none stays in a finished export
        }
        store.finish(runId);
    }

    private void pause(long runId, List<ExportStore.Outcome> batch, String why, String notice) {
        store.save(runId, batch);
        store.pause(runId, why, notice);
        LOG.info("Export {} paused: {}", runId, why);
    }

    /** The export is cancelled: nothing of it is left to sweep. */
    void forget(long runId) {
        partsLeft.remove(runId);
    }

    /**
     * Before copying (a start, a resume, a continue after a restart) and before finishing: removes this export's
     * {@code .part} files (regular files only) of the photos still to copy, and of those done too when a placed copy's
     * {@code .part} could not be removed in this run of the app (a crash between the link and removing it leaves the
     * photo still to copy).
     */
    private void prepare(long runId, Path folder) {
        boolean all = partsLeft.remove(runId);
        for (String rel : all ? store.targets(runId) : store.pendingTargets(runId)) {
            Path part = part(resolve(folder, rel));
            try {
                if (Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) && ExportCleanup.safe(folder, part)) {
                    Files.delete(part);
                }
            } catch (IOException e) {
                LOG.debug("Could not remove {}", part, e);
            }
        }
    }

    /**
     * One photo. A target already there (the app stopped between the move and saving progress, or a file put there
     * meanwhile) is never replaced: the new copy is compared with it, and it counts as done only when they are the
     * same, else the photo fails and the file stays.
     */
    private Copied copyOne(ExportStore.Run run, Path folder, ExportStore.Item item) {
        String rel = livePath(item.imageId());
        if (rel == null) {
            return outcome(item, ExportStore.SKIPPED, DELETED, 0);
        }
        Copied copied = copyFrom(run, folder, item, rel);
        if (ExportStore.SKIPPED.equals(copied.outcome().state()) && MISSING.equals(copied.outcome().reason())) {
            // its file went while it was read: the library may have moved it (a rename, a fix of its species), so its
            // live path is read once more and the copy tried again from there
            String now = livePath(item.imageId());
            if (now == null) {
                return outcome(item, ExportStore.SKIPPED, DELETED, 0);
            }
            if (!now.equals(rel)) {
                return copyFrom(run, folder, item, now);
            }
        }
        return copied;
    }

    /** One photo from its library file at {@code rel}. */
    private Copied copyFrom(ExportStore.Run run, Path folder, ExportStore.Item item, String rel) {
        Path source = resolve(library.get().folder(), rel);
        try {
            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
            if (!attrs.isRegularFile() && !(WINDOWS && attrs.isOther() && !attrs.isDirectory())) {
                return outcome(item, ExportStore.SKIPPED, MISSING, 0);
            }
        } catch (NoSuchFileException e) {
            return outcome(item, ExportStore.SKIPPED, MISSING, 0);
        } catch (IOException e) {
            return outcome(item, ExportStore.FAILED, UNREADABLE, 0);
        }
        SeekableByteChannel channel;
        try {
            channel = Files.newByteChannel(source);
        } catch (NoSuchFileException e) {
            return outcome(item, ExportStore.SKIPPED, MISSING, 0);
        } catch (IOException e) {
            return outcome(item, ExportStore.FAILED, UNREADABLE, 0);
        }
        try (channel) {
            return copyFrom(run, folder, item, source, channel);
        } catch (IOException e) {
            return outcome(item, ExportStore.FAILED, UNREADABLE, 0); // closing a channel only read from
        }
    }

    /**
     * The copy, all read from one open channel (review fix J5): the kind the file is now decides. RAW and HEIC photos go
     * out exactly, never through the stripper or the read-back (7a design §7, Phase 7b design §8), and only when the
     * user's tick accepted that kind; a photo of a kind the tick did not accept (one that became RAW or HEIC since)
     * fails with {@link #NOW_EXACT}. A photo marked exact at the start that is neither any more is copied as any photo
     * (fixes J8); the marks follow what each photo is now, so the counts do (audit 23).
     */
    private Copied copyFrom(ExportStore.Run run, Path folder, ExportStore.Item item, Path source,
                            SeekableByteChannel channel) {
        byte[] data = null;
        String kind;
        try {
            kind = exactKind(channel, source.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            return outcome(item, ExportStore.FAILED, UNREADABLE, 0);
        }
        if (kind != null && !run.acceptedKinds().contains(kind)) {
            return outcome(item, ExportStore.FAILED, NOW_EXACT, 0);
        }
        boolean exact = kind != null;
        if (item.raw() != "raw".equals(kind) || item.heic() != "heic".equals(kind)) {
            if (item.exact() && !exact) {
                LOG.info("Photo {} was {} when export {} started but its file no longer is; copied as any photo",
                        item.imageId(), item.raw() ? "RAW" : "HEIC", run.id());
            }
            store.marks(run.id(), item.seq(), "raw".equals(kind), "heic".equals(kind));
        }
        boolean strip = run.stripLocation() && !exact;
        if (strip) {
            try {
                data = readAll(channel);
            } catch (IOException | OutOfMemoryError e) {
                return outcome(item, ExportStore.FAILED, UNREADABLE, 0);
            }
            try {
                data = stripper.strip(data);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                LOG.debug("Could not remove the location of photo {}", item.imageId(), e);
                return outcome(item, ExportStore.FAILED, NOT_STRIPPED, 0);
            }
        }
        Path target = resolve(folder, item.targetRel());
        Path part = part(target);
        long written = 0;
        try {
            if (!folders(folder, target.getParent())) {
                return outcome(item, ExportStore.FAILED, UNWRITTEN, 0); // something that is not a folder is in the way
            }
            try (OutputStream out = opener.open(part)) {
                if (data != null) {
                    out.write(data);
                    written = data.length;
                } else {
                    written = copy(channel, out);
                }
            }
        } catch (ReadFailure e) {
            deleteQuietly(part);
            if (e.getCause() instanceof NoSuchFileException) {
                return outcome(item, ExportStore.SKIPPED, MISSING, 0); // gone since its attributes were read
            }
            return outcome(item, ExportStore.FAILED, UNREADABLE, 0);
        } catch (IOException e) {
            LOG.debug("Could not write {}", part, e);
            deleteQuietly(part);
            return new Copied(new ExportStore.Outcome(item.seq(), ExportStore.FAILED, UNWRITTEN, 0), true);
        }
        if (strip) {
            try {
                if (stripper.hasLocation(part)) {
                    deleteQuietly(part);
                    return outcome(item, ExportStore.FAILED, STILL_LOCATED, 0);
                }
            } catch (IOException e) {
                deleteQuietly(part);
                return outcome(item, ExportStore.FAILED, NOT_STRIPPED, 0);
            }
        }
        try {
            BasicFileAttributes there = attributes(target);
            if (there != null) {
                // only a regular file is compared (never opened otherwise: a link, a folder, a FIFO): kept either way
                boolean same = there.isRegularFile() && sameContent(part, target);
                deleteQuietly(part);
                // the same bytes: done, without a time, as this export may not have written it (Remove keeps it)
                return same ? new Copied(new ExportStore.Outcome(item.seq(), ExportStore.ITEM_DONE, null, written, null),
                        false) : outcome(item, ExportStore.FAILED, UNWRITTEN, 0);
            }
            if (place(part, target)) {
                partsLeft.add(run.id());
            }
        } catch (FileAlreadyExistsException e) {
            deleteQuietly(part); // made in the gap: kept, and this photo's own failure
            return outcome(item, ExportStore.FAILED, UNWRITTEN, 0);
        } catch (IOException e) {
            LOG.debug("Could not move {} into place", part, e);
            deleteQuietly(part);
            return new Copied(new ExportStore.Outcome(item.seq(), ExportStore.FAILED, UNWRITTEN, 0), true);
        }
        return done(item, target, written);
    }

    /**
     * {@code raw} or {@code heic} when the file is one by its bytes (a TIFF-shaped RAW by its extension or its
     * DNGVersion, as {@link ImageIoPhotoProcessor#isRaw} decides), else null; read from {@code channel}, which is left
     * at its start.
     */
    static String exactKind(SeekableByteChannel channel, String fileName) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(PhotoFormat.HEAD_BYTES);
        channel.position(0);
        while (head.hasRemaining() && channel.read(head) > 0) {
            // keep reading: a channel may return fewer bytes than asked
        }
        PhotoFormat format = PhotoFormat.detect(java.util.Arrays.copyOf(head.array(), head.position())).orElse(null);
        String kind = null;
        if (format == PhotoFormat.RAW || (format == PhotoFormat.TIFF && (PhotoFormat.isRawFileName(fileName)
                || RawPreviews.family(channel).isPresent()))) {
            kind = "raw";
        } else if (format == PhotoFormat.HEIC) {
            kind = "heic";
        }
        channel.position(0);
        return kind;
    }

    /** The whole file from the channel's start. */
    private static byte[] readAll(SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        if (size > Integer.MAX_VALUE - 16) {
            throw new IOException("Too large to rewrite in memory");
        }
        ByteBuffer all = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (all.hasRemaining() && channel.read(all) > 0) {
            // keep reading
        }
        return java.util.Arrays.copyOf(all.array(), all.position());
    }

    /** Done: with the size and time of the file at the target, so Remove deletes it only while it is unchanged. */
    private static Copied done(ExportStore.Item item, Path target, long written) {
        Long mtime = null;
        try {
            mtime = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).lastModifiedTime()
                    .toMillis();
        } catch (IOException e) {
            LOG.debug("Could not read the time of {}", target, e);
        }
        return new Copied(new ExportStore.Outcome(item.seq(), ExportStore.ITEM_DONE, null, written, mtime), false);
    }

    private static Copied outcome(ExportStore.Item item, String state, String reason, long bytes) {
        return new Copied(new ExportStore.Outcome(item.seq(), state, reason, bytes), false);
    }

    /** The photo's live {@code rel_path}; null when it is deleted (Recently deleted, or gone). */
    private String livePath(long imageId) {
        return library.get().database().read(c -> {
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

    /** Streams the original into {@code out} unchanged from the channel's start; returns the bytes written. */
    private static long copy(SeekableByteChannel channel, OutputStream out) throws IOException {
        long total = 0;
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        try {
            channel.position(0);
        } catch (IOException e) {
            throw new ReadFailure(e);
        }
        while (true) {
            int n;
            try {
                buffer.clear();
                n = channel.read(buffer);
            } catch (IOException e) {
                throw new ReadFailure(e);
            }
            if (n < 0) {
                return total;
            }
            out.write(buffer.array(), 0, n);
            total += n;
        }
    }

    /**
     * Puts the {@code .part} at the target without ever replacing a file: a hard link (fails if the target exists) and
     * the {@code .part} removed, on POSIX; a move without options on Windows (MoveFileEx without replace fails if the
     * target exists). Where hard links are not supported (FAT, exFAT, some network shares) a move without options:
     * Java checks the target is absent, then renames (a file made in that tiny gap could be replaced: accepted). True
     * when the copy is in place but its {@code .part} could not be removed (a second link to it).
     */
    boolean place(Path part, Path target) throws IOException {
        beforeMove.accept(target);
        return place(part, target, linker, partDeleter);
    }

    /** {@link #place(Path, Path)} with the way hard links are made and a {@code .part} deleted given (backups share it). */
    public static boolean place(Path part, Path target, Linker linker, Deleter partDeleter) throws IOException {
        if (!WINDOWS) {
            boolean linked = false;
            try {
                linker.link(target, part);
                linked = true;
            } catch (FileAlreadyExistsException e) {
                throw e;
            } catch (UnsupportedOperationException | FileSystemException e) {
                LOG.debug("No hard link for {}: {}", target, e.toString());
            }
            if (linked) {
                try {
                    partDeleter.delete(part); // the copy is in place: a .part left over is swept later
                    return false;
                } catch (IOException e) {
                    LOG.warn("Could not remove {}", part, e);
                    return true;
                }
            }
        }
        Files.move(part, target);
        return false;
    }

    /**
     * Makes the folders from the export folder down to {@code parent}, one level at a time, never the export folder
     * itself (for a photo in it, nothing); false when something that is not a real folder (a file, a link) is in the way.
     */
    private boolean folders(Path folder, Path parent) throws IOException {
        if (parent.equals(folder)) {
            return true; // relativize would give one empty name, and createDirectory would make a vanished folder again
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

    /**
     * A folder read without following links: a directory, not a symbolic link; one that reads as "other" (a Windows
     * reparse point: an OneDrive folder, or a junction) only when its real path is itself, so a junction is never written
     * through.
     */
    private boolean realFolder(Path path) {
        BasicFileAttributes attrs;
        try {
            attrs = attributes.read(path);
        } catch (IOException e) {
            return false;
        }
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) {
            return false;
        }
        if (!attrs.isOther()) {
            return true;
        }
        try {
            return path.toRealPath().equals(path.getParent().toRealPath().resolve(path.getFileName()));
        } catch (IOException e) {
            return false;
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether two files hold the same bytes (sizes first). */
    private static boolean sameContent(Path a, Path b) throws IOException {
        if (Files.size(a) != Files.size(b)) {
            return false;
        }
        return Files.mismatch(a, b) == -1;
    }

    public static Path part(Path target) {
        return target.resolveSibling(target.getFileName() + ExportNames.PART);
    }

    /** A {@code /}-separated relative path under {@code folder}. */
    public static Path resolve(Path folder, String rel) {
        Path path = folder;
        for (String part : rel.split("/")) {
            if (!part.isEmpty()) {
                path = path.resolve(part);
            }
        }
        return path;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
        }
    }
}
