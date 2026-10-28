package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.library.Library;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The image cache's files: where a photo's copy comes from and goes, whether it is still current, moving one into place. */
final class CacheFiles {

    private CacheFiles() {
    }

    /**
     * Where a photo's cached copy comes from (its file, as it is now) and where it goes ({@link #target}); its stored file
     * type (null for a photo of an older library not read yet).
     */
    record Source(long id, Path file, int orientation, String modifiedAt, long fileSize, Path folder, int version,
                  String fileType) {

        Path target(int size) {
            return folder.resolve(Thumbnails.fileName(id, size, modifiedAt, fileSize, version));
        }

        /** A HEIC photo: its copies need the computer's decoder (Phase 7b). */
        boolean heic() {
            return PhotoFormat.HEIC.fileType().equals(fileType);
        }
    }

    /**
     * The photo's source as the database has it now, or NOT_FOUND.
     *
     * @param inWriteTransaction read after any write in progress commits (see {@link CopyMaker#movedSince})
     */
    static Source source(Library lib, long id, int version, boolean inWriteTransaction) {
        Database.Work<Source> read = c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT CASE WHEN deleted_at IS NULL THEN rel_path"
                    + " ELSE deleted_rel_path END, modified_at, coalesce(orientation, 1), deleted_at IS NOT NULL, file_size,"
                    + " file_type FROM image WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null) {
                        throw AtlasException.noPhoto(id);
                    }
                    Path file = PhotoFiles.resolve(lib.folder(), rs.getString(1));
                    if (rs.getBoolean(4) && !Files.exists(file)) {
                        // A deleted photo whose file is missing has nothing to show.
                        throw AtlasException.noPhoto(id);
                    }
                    return new Source(id, file, rs.getInt(3), rs.getString(2), rs.getLong(5),
                            lib.folder().resolve(Thumbnails.FOLDER), version, rs.getString(6));
                }
            }
        };
        return inWriteTransaction ? lib.database().write(read) : lib.database().read(read);
    }

    /** Sources of those of {@code ids} that are live, in id order. */
    static List<Source> liveSources(Connection c, Path data, List<Long> ids, int version) throws SQLException {
        List<Source> sources = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, modified_at, coalesce(orientation, 1), file_size,"
                + " file_type FROM image WHERE deleted_at IS NULL AND id IN (" + SqlLists.marks(ids.size()) + ") ORDER BY id")) {
            SqlLists.bind(ps, 1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sources.add(new Source(rs.getLong(1), PhotoFiles.resolve(data, rs.getString(2)), rs.getInt(4),
                            rs.getString(3), rs.getLong(5), data.resolve(Thumbnails.FOLDER), version, rs.getString(6)));
                }
            }
        }
        return sources;
    }

    /** Whether the photo still has the file version {@code source} names (same modified_at and size). */
    static boolean current(Connection c, Source source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM image WHERE id = ? AND modified_at IS ? AND file_size IS ?")) {
            ps.setLong(1, source.id());
            ps.setString(2, source.modifiedAt());
            ps.setLong(3, source.fileSize());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Moves an import's preview to the thumbnail's name, never deleting or replacing a file already there: one that
     * appeared meanwhile (made by a request) is the same picture, so the preview is dropped instead.
     */
    static void movePreview(Path preview, Path thumb) throws IOException {
        Files.createDirectories(thumb.getParent());
        try {
            try {
                Files.move(preview, thumb, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(preview, thumb);
            }
        } catch (FileAlreadyExistsException e) {
            PhotoFiles.deleteQuietly(preview);
        }
    }
}
