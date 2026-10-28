package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Removes the files purges left behind in Recently deleted ({@code .deleted}), fail-safe; run at startup. */
final class DeletedSweep {

    private static final Logger LOG = LoggerFactory.getLogger(DeletedSweep.class);

    private final Supplier<Library> library;

    DeletedSweep(Supplier<Library> library) {
        this.library = library;
    }

    /** A file in {@code .deleted} younger than this is never swept, whatever else holds ({@link #sweep}). */
    static final Duration DELETED_GRACE = Duration.ofMinutes(2);

    /**
     * Removes the files a purge left behind in {@code .deleted} (its file delete failed) and nothing else. It is
     * fail-safe: a top-level file goes only when no photo, live or in Recently deleted, points to it through
     * {@code deleted_rel_path}, the photo id in its name ({@code <id>_...}) belongs to no photo at all, the
     * change log holds a purge of exactly that path, and it is untouched for {@link #DELETED_GRACE}. Anything
     * else (a file an undo could not put back, an unknown file) is kept, and logged once per sweep as a
     * warning; the rescan puts back a photo's own file. The folder is listed and checked, and the rows read,
     * outside any write transaction (a large Recently deleted must not hold the write lock); then each candidate is
     * checked again and removed in a short write transaction ({@link #SWEEP_BATCH} at a time): a delete or restore
     * moves its files under the same lock, so a file on its way in or out is never taken for a leftover. Each
     * removal is logged; returns how many went. Called at startup.
     */
    int sweep() {
        Library lib = library.get();
        Path folder = lib.folder().resolve(PhotoFiles.DELETED);
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(DELETED_GRACE); // file times are real, whatever the app's clock says
        // 1. Outside the write lock: what is there and old enough, and what the rows say about it.
        List<Path> old = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                try {
                    if (Files.isRegularFile(file) && !Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
                        old.add(file);
                    }
                } catch (IOException e) {
                    LOG.warn("Could not check {} in Recently deleted", file, e);
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException e) {
            LOG.warn("Could not list Recently deleted", e);
        }
        if (old.isEmpty()) {
            return 0;
        }
        Set<String> wanted = new HashSet<>();
        Set<Long> photoIds = new HashSet<>();
        Set<String> purged = new HashSet<>();
        lib.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, deleted_rel_path FROM image");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photoIds.add(rs.getLong(1));
                    if (rs.getString(2) != null) {
                        wanted.add(rs.getString(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT json_extract(payload_json, '$.fromPath') FROM change_log"
                    + " WHERE kind = ?")) {
                ps.setString(1, ChangeLog.PURGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getString(1) != null) {
                            purged.add(rs.getString(1));
                        }
                    }
                }
            }
            return null;
        });
        List<Path> candidates = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Path file : old) {
            String name = file.getFileName().toString();
            String relPath = PhotoFiles.DELETED + "/" + name;
            if (wanted.contains(relPath)) {
                continue;
            }
            Long id = idPrefix(name);
            if (id != null && photoIds.contains(id) || !purged.contains(relPath)) {
                kept.add(name);
                continue;
            }
            candidates.add(file);
        }
        // 2. Each candidate checked again and removed under the write lock, a short transaction per batch.
        int removed = 0;
        for (int from = 0; from < candidates.size(); from += SWEEP_BATCH) {
            List<Path> batch = candidates.subList(from, Math.min(from + SWEEP_BATCH, candidates.size()));
            removed += lib.database().write(c -> {
                int gone = 0;
                for (Path file : batch) {
                    String name = file.getFileName().toString();
                    try {
                        if (!stillLeftover(c, file, name, cutoff)) {
                            continue; // changed since it was listed: a photo points to it now, or it is new
                        }
                    } catch (IOException e) {
                        LOG.warn("Could not check {} in Recently deleted", file, e);
                        continue;
                    }
                    if (PhotoFiles.deleteQuietly(file)) {
                        LOG.info("Removed {} from Recently deleted: its photo was removed for good", name);
                        gone++;
                    }
                }
                return gone;
            });
        }
        if (!kept.isEmpty()) {
            LOG.warn("{} files in Recently deleted were kept: no removal for good is on record for them (first {}): {}",
                    kept.size(), Math.min(kept.size(), 10), kept.subList(0, Math.min(kept.size(), 10)));
        }
        return removed;
    }

    /** Leftover candidates checked again and removed per write transaction by {@link #sweep}. */
    static final int SWEEP_BATCH = 100;

    /**
     * Every fail-safe rule of {@link #sweep}, checked again inside its write transaction: still a regular file
     * untouched for {@link #DELETED_GRACE}, no photo points to it, its id prefix belongs to no photo, and a purge of
     * exactly that path is on record.
     */
    private static boolean stillLeftover(Connection c, Path file, String name, Instant cutoff) throws IOException, SQLException {
        if (!Files.isRegularFile(file) || Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
            return false;
        }
        String relPath = PhotoFiles.DELETED + "/" + name;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM image WHERE deleted_rel_path = ?")) {
            ps.setString(1, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false;
                }
            }
        }
        Long id = idPrefix(name);
        if (id != null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM image WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM change_log WHERE kind = ?"
                + " AND json_extract(payload_json, '$.fromPath') = ?")) {
            ps.setString(1, ChangeLog.PURGE);
            ps.setString(2, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The photo id a {@code .deleted} file name starts with ({@code <id>_<name>}), or null. */
    private static Long idPrefix(String name) {
        int underscore = name.indexOf('_');
        if (underscore <= 0) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(0, underscore));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
