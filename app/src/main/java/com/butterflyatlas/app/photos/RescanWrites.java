package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.DatabaseBusy;
import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * What the rescan writes (design §7): new photos registered in batches, records following a renamed or edited file,
 * a file a failed undo left in Recently deleted put back, and records of files that are gone removed; each in its own
 * write transaction, re-checked there.
 */
final class RescanWrites {

    private static final Logger LOG = LoggerFactory.getLogger(RescanWrites.class);

    /** A live photo's record as the rescan compares it with the files. */
    record Row(long id, String taxonId, String relPath, String fileName, long size, String modifiedAt, String sha256) {
    }

    /** A supported file found in a species folder, settled (not still being copied). */
    record Candidate(String taxonId, Path file, String relPath, long size, Instant modified) {
    }

    /** A file the library does not know, read and waiting to be registered with its batch. */
    record NewPhoto(Candidate file, PhotoIngest.Result read) {
    }

    private final PhotoIngest ingest;
    private final ProcessingLedger ledger;
    private final FactConsumers facts;
    private final Clock clock;
    private final LongConsumer found;
    /** Told of a file the database stayed busy for, after its last try: the rescan lists it with its reason. */
    private volatile java.util.function.Consumer<NewPhoto> busy = photo -> { };
    /** Runs inside each registering transaction, with the photos it registers (tests make it busy there). */
    volatile java.util.function.Consumer<List<NewPhoto>> beforeInsert = photos -> { };

    RescanWrites(PhotoIngest ingest, Clock clock, LongConsumer found, List<PhotoConsumer> consumers) {
        this.ingest = ingest;
        this.ledger = new ProcessingLedger(clock);
        this.facts = new FactConsumers(consumers, ledger);
        this.clock = clock;
        this.found = found;
    }

    /** Width, height and orientation from the facts; null, null, 1 for a photo whose details could not be read. */
    private static void setInfo(PreparedStatement ps, int at, PhotoFacts facts) throws SQLException {
        Integer width = facts == null ? null : facts.width();
        Integer height = facts == null ? null : facts.height();
        if (width != null) {
            ps.setInt(at, width);
        } else {
            ps.setNull(at, Types.INTEGER);
        }
        if (height != null) {
            ps.setInt(at + 1, height);
        } else {
            ps.setNull(at + 1, Types.INTEGER);
        }
        ps.setInt(at + 2, facts == null ? 1 : facts.orientation());
    }

    /**
     * Stores the snapshot that goes with the row's width, height and orientation, and has every fact consumer process
     * the photo from it; drops a stale snapshot if the photo could not be read (the details catch-up tries it again).
     */
    private void saveFacts(Connection c, long id, PhotoFacts read, Path file) throws SQLException {
        if (read != null) {
            ImageMeta.write(c, id, read, clock.instant());
            facts.applyAll(c, List.of(new PhotoConsumer.Subject(id, read, file)));
        } else {
            ImageMeta.delete(c, id);
        }
    }

    /** Tells the listener; its failure (a thumbnail that cannot be queued) must not undo or hide what was found. */
    void notifyFound(long id) {
        try {
            found.accept(id);
        } catch (RuntimeException e) {
            LOG.warn("Rescan could not pass on photo {}", id, e);
        }
    }

    /**
     * A live record whose file is gone while {@code .deleted/<id>_<file name>} exists: a delete's undo that could not
     * put the file back (e.g. it was locked on Windows). Moves it back to the record's path, never over an existing
     * file; when that is not possible both stay, with a warning. Inside a write transaction, so a delete or restore
     * in progress (which holds the same lock) is never mistaken for it, and the record is re-checked there.
     */
    boolean putBack(Library lib, Row row) {
        String deletedRelPath = PhotoFiles.DELETED + "/" + row.id() + "_" + row.fileName();
        Path from = PhotoFiles.resolve(lib.folder(), deletedRelPath);
        Path to = PhotoFiles.resolve(lib.folder(), row.relPath());
        if (!Files.isRegularFile(from)) {
            return false;
        }
        boolean[] moved = {false};
        try {
            return lib.database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM image WHERE id = ? AND rel_path = ?"
                        + " AND deleted_at IS NULL")) {
                    ps.setLong(1, row.id());
                    ps.setString(2, row.relPath());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return false;
                        }
                    }
                }
                if (Files.exists(to) || !Files.isDirectory(to.getParent()) || !Files.isRegularFile(from)) {
                    LOG.warn("Rescan: {} is missing and {} holds its file, but it could not be put back; both are kept",
                            row.relPath(), deletedRelPath);
                    return false;
                }
                try {
                    PhotoFiles.move(from, to);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                moved[0] = true;
                ChangeLog.record(c, clock.instant(), ChangeLog.RESTORE, ChangeLog.payload("id", row.id(),
                        "taxonId", row.taxonId(), "fromPath", deletedRelPath, "toPath", row.relPath(), "recovered", true));
                LOG.info("Rescan: put {} back at {}", deletedRelPath, row.relPath());
                return true;
            }, () -> {
                if (moved[0]) {
                    try {
                        PhotoFiles.move(to, from);
                    } catch (IOException | RuntimeException e) {
                        LOG.error("Could not put {} back at {}", to, from, e);
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Rescan: {} is missing and {} holds its file, but it could not be put back; both are kept",
                    row.relPath(), deletedRelPath, e);
            return false;
        }
    }

    /** Points a record at the file that took over its content (renamed or moved within the species), keeping tags, number and cover. */
    boolean follow(Library lib, Row row, Candidate file) {
        return lib.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET rel_path = ?, file_name = ?, modified_at = ?"
                    + " WHERE id = ? AND rel_path = ? AND deleted_at IS NULL")) {
                ps.setString(1, file.relPath());
                ps.setString(2, file.file().getFileName().toString());
                ps.setString(3, file.modified().toString());
                ps.setLong(4, row.id());
                ps.setString(5, row.relPath());
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            ChangeLog.record(c, clock.instant(), ChangeLog.RENAME, ChangeLog.payload("id", row.id(),
                    "taxonId", row.taxonId(), "fromPath", row.relPath(), "toPath", file.relPath()));
            return true;
        });
    }

    /**
     * A known file that was edited in place: re-reads it so size, hash, dimensions and modified time (the cache
     * key) follow, makes every consumer process it again, and rewrites its snapshot. An edit whose details cannot be
     * read keeps the photo's old GPS place (its snapshot goes; the details catch-up tries again at the next start).
     */
    boolean refresh(Library lib, Row row, Candidate file) {
        PhotoIngest.Result read;
        try {
            try {
                read = ingest.ingest(file.file());
            } catch (AtlasException e) {
                // No longer a photo the app supports: keep the record (as 3a did), without a size.
                read = new PhotoIngest.Result(PhotoFiles.sha256(file.file()), file.size(), null, null);
            }
        } catch (IOException e) {
            LOG.warn("Could not read {}", file.file(), e);
            return false;
        }
        PhotoIngest.Result fresh = read;
        boolean updated = lib.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET modified_at = ?, file_size = ?, sha256 = ?,"
                    + " width = ?, height = ?, orientation = ?, file_type = coalesce(?, file_type) WHERE id = ? AND rel_path = ?"
                    + " AND deleted_at IS NULL")) {
                ps.setString(1, file.modified().toString());
                ps.setLong(2, file.size());
                ps.setString(3, fresh.sha256());
                setInfo(ps, 4, fresh.facts());
                ps.setString(7, fresh.format() == null ? null : fresh.format().fileType());
                ps.setLong(8, row.id());
                ps.setString(9, row.relPath());
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            ledger.forget(c, row.id());
            saveFacts(c, row.id(), fresh.facts(), file.file());
            return true;
        });
        if (updated) {
            notifyFound(row.id());
        }
        return updated;
    }

    /**
     * Registers new photos in one transaction (one per photo if that fails, so one bad row cannot hold back
     * the rest); returns how many went in. A file the app added meanwhile keeps its record (INSERT OR IGNORE).
     */
    int register(Library lib, List<NewPhoto> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        List<Long> ids;
        try {
            ids = lib.database().write(c -> insert(c, batch));
        } catch (RuntimeException e) {
            LOG.warn("Rescan could not register {} photos together; trying one at a time", batch.size(), e);
            ids = new ArrayList<>();
            for (NewPhoto photo : batch) {
                try {
                    ids.addAll(DatabaseBusy.retry("registering a photo the rescan found",
                            () -> lib.database().write(c -> insert(c, List.of(photo)))));
                } catch (RuntimeException single) {
                    if (DatabaseBusy.is(single)) {
                        skippedBusy(photo, single); // its facts are not the trouble: listed, not registered bare
                        continue;
                    }
                    ids.addAll(registerWithoutFacts(lib, photo, single));
                }
            }
        }
        ids.forEach(this::notifyFound);
        return ids.size();
    }

    /**
     * A photo whose row or snapshot could not be saved is registered once more without its facts (sizes NULL,
     * no snapshot; the catch-up reads it later), as 3a would have; if that fails too it is skipped.
     */
    private List<Long> registerWithoutFacts(Library lib, NewPhoto photo, RuntimeException first) {
        LOG.warn("Rescan could not save the details of {}; registering it without them", photo.file().file(), first);
        PhotoIngest.Result read = photo.read();
        NewPhoto bare = new NewPhoto(photo.file(), new PhotoIngest.Result(read.sha256(), read.size(), read.format(), null));
        try {
            return DatabaseBusy.retry("registering a photo the rescan found",
                    () -> lib.database().write(c -> insert(c, List.of(bare))));
        } catch (RuntimeException second) {
            if (DatabaseBusy.is(second)) {
                skippedBusy(photo, second);
            } else {
                LOG.warn("Rescan skipped {}", photo.file().file(), second);
            }
            return List.of();
        }
    }

    /** Where a file the database stayed busy for is told (the rescan lists it in {@code rescanLeft}). */
    void onBusy(java.util.function.Consumer<NewPhoto> busy) {
        this.busy = busy;
    }

    private void skippedBusy(NewPhoto photo, RuntimeException e) {
        LOG.warn("Rescan skipped {}: the database stayed busy", photo.file().file(), e);
        busy.accept(photo);
    }

    private List<Long> insert(Connection c, List<NewPhoto> photos) throws SQLException {
        beforeInsert.accept(photos);
        List<Long> ids = new ArrayList<>();
        // OR IGNORE: if the app added this very file meanwhile, its record wins (image_live_path).
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO image (taxon_id, rel_path, file_name,"
                + " file_size, modified_at, sha256, width, height, orientation, added_at, source, file_type)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'rescan', ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
            for (NewPhoto photo : photos) {
                Candidate file = photo.file();
                ps.setString(1, file.taxonId());
                ps.setString(2, file.relPath());
                ps.setString(3, file.file().getFileName().toString());
                ps.setLong(4, file.size());
                ps.setString(5, file.modified().toString());
                ps.setString(6, photo.read().sha256());
                setInfo(ps, 7, photo.read().facts());
                ps.setString(10, clock.instant().toString());
                PhotoFormat format = photo.read().format();
                ps.setString(11, format == null ? null : format.fileType());
                if (ps.executeUpdate() == 0) {
                    continue;
                }
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
                saveFacts(c, id, photo.read().facts(), file.file());
                ids.add(id);
            }
        }
        return ids;
    }

    /** Removes the record of a photo whose file is gone, unless it changed meanwhile (moved, deleted). */
    boolean remove(Library lib, long id, String relPath) {
        return lib.database().write(c -> {
            if (Files.isRegularFile(PhotoFiles.resolve(lib.folder(), relPath))) {
                return false;
            }
            int removed;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM image WHERE id = ? AND rel_path = ? AND deleted_at IS NULL")) {
                ps.setLong(1, id);
                ps.setString(2, relPath);
                removed = ps.executeUpdate();
            }
            if (removed == 0) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET cover_image_id = NULL WHERE cover_image_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            ChangeLog.record(c, clock.instant(), ChangeLog.MISSING, ChangeLog.payload("id", id, "relPath", relPath));
            return true;
        });
    }
}
