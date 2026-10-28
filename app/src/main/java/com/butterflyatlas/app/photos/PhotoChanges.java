package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.taxa.TaxonSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Moving live photos to another species, deleting them to Recently deleted, restoring them and purging them for good
 * (design §6). The database and the folders change together; when a step fails, files are put back.
 */
final class PhotoChanges {

    private final Supplier<Library> library;
    private final Clock clock;
    private final DeletedCount deleted;

    PhotoChanges(Supplier<Library> library, Clock clock, DeletedCount deleted) {
        this.library = library;
        this.clock = clock;
        this.deleted = deleted;
    }

    /**
     * Moves live photos to another species. Each file is renamed with the target's next numbers
     * (design P3); a cover that pointed at a moved photo goes back to automatic. All or nothing.
     */
    List<Photo> move(List<Long> rawIds, String taxonId) {
        List<Long> ids = ids(rawIds);
        Library lib = library.get();
        FileMoves moves = new FileMoves();
        return lib.database().write(c -> {
            PhotoRows.species(c, taxonId);
            TaxonSnapshot snapshot = TaxonSnapshot.load(c); // once per call: a big batch holds the write lock
            Instant now = clock.instant();
            for (long id : ids) {
                Photo photo = PhotoRows.live(c, id);
                if (photo.taxonId().equals(taxonId)) {
                    continue;
                }
                PhotoNames.Named named = PhotoNames.freeName(c, snapshot, lib.folder(), taxonId, PhotoNames.extension(photo.fileName()), PhotoNames.nextNumber(c, taxonId));
                moves.move(PhotoFiles.resolve(lib.folder(), photo.relPath()), named.file(), photo.fileName());
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE image SET taxon_id = ?, rel_path = ?, file_name = ?, number = ? WHERE id = ?")) {
                    ps.setString(1, taxonId);
                    ps.setString(2, named.relPath());
                    ps.setString(3, named.fileName());
                    ps.setInt(4, named.number());
                    ps.setLong(5, id);
                    ps.executeUpdate();
                }
                PhotoNames.setNextNumber(c, taxonId, named.number() + 1);
                Covers.clearCover(c, id);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM split_photo WHERE image_id = ?")) {
                    ps.setLong(1, id); // sorted (5b contract §5): no longer waiting on the split species
                    ps.executeUpdate();
                }
                ChangeLog.record(c, now, ChangeLog.MOVE, ChangeLog.payload("id", id, "taxonId", taxonId,
                        "fromPath", photo.relPath(), "toPath", named.relPath()));
            }
            List<Photo> moved = new ArrayList<>();
            for (long id : ids) {
                moved.add(PhotoRows.live(c, id));
            }
            return moved;
        }, moves::undo);
    }

    /**
     * Moves live photos to Recently deleted ({@code .deleted/<id>_<name>}); returns how many. A photo
     * whose file is already gone is deleted all the same, with no {@code deleted_rel_path}.
     */
    int delete(List<Long> rawIds) {
        List<Long> ids = ids(rawIds);
        Library lib = library.get();
        FileMoves moves = new FileMoves();
        return deleted.change(lib.database(), c -> {
            Instant now = clock.instant();
            int deleted = 0;
            for (long id : ids) {
                Photo photo = PhotoRows.live(c, id);
                Path file = PhotoFiles.resolve(lib.folder(), photo.relPath());
                boolean missing = !Files.exists(file);
                String deletedRelPath = missing ? null : PhotoFiles.DELETED + "/" + id + "_" + photo.fileName();
                if (!missing) {
                    moves.move(file, PhotoFiles.resolve(lib.folder(), deletedRelPath), photo.fileName());
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE image SET deleted_at = ?, deleted_rel_path = ? WHERE id = ?")) {
                    ps.setString(1, now.toString());
                    ps.setString(2, deletedRelPath);
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
                Covers.clearCover(c, id);
                Map<String, Object> payload = ChangeLog.payload("id", id, "taxonId", photo.taxonId(),
                        "fromPath", photo.relPath(), "toPath", deletedRelPath);
                if (missing) {
                    payload.put("missing", true);
                }
                ChangeLog.record(c, now, ChangeLog.DELETE, payload);
                deleted++;
            }
            return deleted;
        }, moves::undo);
    }

    /**
     * Puts photos from Recently deleted back in their species' current folder: under the old name
     * when it is free there, else under the species' next free number. A photo whose file was
     * already missing when it was deleted comes back without a file (the next rescan drops it). A photo restored
     * without a place it is due (deleted before the place consumer caught up) gets it at the next start's catch-up.
     */
    List<Photo> restore(List<Long> rawIds) {
        List<Long> ids = ids(rawIds);
        Library lib = library.get();
        FileMoves moves = new FileMoves();
        return deleted.change(lib.database(), c -> {
            Instant now = clock.instant();
            TaxonSnapshot snapshot = TaxonSnapshot.load(c); // once per call: a big batch holds the write lock
            for (long id : ids) {
                Deleted d = deletedRow(c, id);
                TaxonSnapshot.Row row = snapshot.find(d.taxonId())
                        .orElseThrow(() -> AtlasException.noTaxon(d.taxonId()));
                String oldRelPath = snapshot.folder(row) + d.fileName();
                Path old = PhotoFiles.resolve(lib.folder(), oldRelPath);
                PhotoNames.Named named;
                if (!PhotoRows.livePathTaken(c, oldRelPath) && !Files.exists(old)) {
                    named = new PhotoNames.Named(d.number(), d.fileName(), oldRelPath, old);
                } else {
                    named = PhotoNames.freeName(c, snapshot, lib.folder(), d.taxonId(), PhotoNames.extension(d.fileName()), PhotoNames.nextNumber(c, d.taxonId()));
                    PhotoNames.setNextNumber(c, d.taxonId(), named.number() + 1);
                }
                if (d.deletedRelPath() != null) {
                    moves.move(PhotoFiles.resolve(lib.folder(), d.deletedRelPath()), named.file(), d.fileName());
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE image SET deleted_at = NULL,"
                        + " deleted_rel_path = NULL, rel_path = ?, file_name = ?, number = ? WHERE id = ?")) {
                    ps.setString(1, named.relPath());
                    ps.setString(2, named.fileName());
                    ps.setObject(3, named.number());
                    ps.setLong(4, id);
                    ps.executeUpdate();
                }
                ChangeLog.record(c, now, ChangeLog.RESTORE, ChangeLog.payload("id", id, "taxonId", d.taxonId(),
                        "fromPath", d.deletedRelPath(), "toPath", named.relPath()));
            }
            List<Photo> restored = new ArrayList<>();
            for (long id : ids) {
                restored.add(PhotoRows.live(c, id));
            }
            return restored;
        }, moves::undo);
    }

    /**
     * Removes photos in Recently deleted for good: the given ones, or all of them for an empty list.
     * Ids that are unknown or not in Recently deleted are ignored.
     */
    int purge(List<Long> idsOrEmptyForAll) {
        List<Long> wanted = ids(idsOrEmptyForAll == null ? List.of() : idsOrEmptyForAll, true);
        Library lib = library.get();
        List<Path> files = new ArrayList<>();
        int removed = deleted.change(lib.database(), c -> {
            Instant now = clock.instant();
            List<Deleted> doomed = new ArrayList<>();
            if (wanted.isEmpty()) {
                List<Long> all = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM image WHERE deleted_at IS NOT NULL");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        all.add(rs.getLong(1));
                    }
                }
                for (long id : all) {
                    doomed.add(deletedRow(c, id));
                }
            } else {
                for (long id : wanted) {
                    if (isDeleted(c, id)) {
                        doomed.add(deletedRow(c, id));
                    }
                }
            }
            for (Deleted d : doomed) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM image WHERE id = ?")) {
                    ps.setLong(1, d.id());
                    ps.executeUpdate(); // its tags go with it (ON DELETE CASCADE)
                }
                Covers.clearCover(c, d.id());
                ChangeLog.record(c, now, ChangeLog.PURGE, ChangeLog.payload("id", d.id(), "taxonId", d.taxonId(),
                        "fromPath", d.deletedRelPath(), "toPath", null, "fileName", d.fileName(), "sha256", d.sha256()));
                if (d.deletedRelPath() != null) {
                    files.add(PhotoFiles.resolve(lib.folder(), d.deletedRelPath()));
                }
            }
            return doomed.size();
        }, () -> { });
        files.forEach(PhotoFiles::deleteQuietly);
        return removed;
    }

    /** Removes for good everything that has been in Recently deleted longer than {@code age}. */
    int purgeOlderThan(Duration age) {
        Instant cutoff = clock.instant().minus(age);
        List<Long> old = library.get().database().read(c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, deleted_at FROM image WHERE deleted_at IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (Instant.parse(rs.getString(2)).isBefore(cutoff)) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
        return old.isEmpty() ? 0 : purge(old);
    }

    private record Deleted(long id, String taxonId, String relPath, String fileName, Integer number,
                           String deletedRelPath, String sha256) {
    }

    private static boolean isDeleted(Connection c, long id) throws SQLException {
        return PhotoRows.count(c, "SELECT COUNT(*) FROM image WHERE id = ? AND deleted_at IS NOT NULL",
                ps -> ps.setLong(1, id)) > 0;
    }

    private static Deleted deletedRow(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT taxon_id, rel_path, file_name, number, deleted_rel_path,"
                + " sha256 FROM image WHERE id = ? AND deleted_at IS NOT NULL")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AtlasException.notFound("There is no photo " + id + " in Recently deleted.");
                }
                int number = rs.getInt(4);
                Integer maybeNumber = rs.wasNull() ? null : number;
                return new Deleted(id, rs.getString(1), rs.getString(2), rs.getString(3), maybeNumber, rs.getString(5),
                        rs.getString(6));
            }
        }
    }

    private static List<Long> ids(List<Long> raw) {
        return ids(raw, false);
    }

    private static List<Long> ids(List<Long> raw, boolean emptyAllowed) {
        List<Long> ids = raw == null ? List.of() : raw.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() && !emptyAllowed) {
            throw AtlasException.invalid(AtlasException.NO_PHOTOS_CHOSEN);
        }
        return ids;
    }
}
