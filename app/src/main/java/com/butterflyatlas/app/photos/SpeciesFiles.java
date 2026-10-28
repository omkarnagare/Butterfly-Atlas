package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A species' photo files when the species is renamed (5a contract §3): inside the caller's database write, every live
 * photo moves to the new folder, its file renamed from {@code <Old_name>_NN.ext} to {@code <New_name>_NN.ext} (a file
 * named by hand keeps its name); photos in Recently deleted get the new name for their restore (their files in
 * {@code .deleted/} stay). The caller passes {@link #undo} to {@code Database.write(work, undo)}, and once the write has
 * committed calls {@link #removeOldFolder} for the old folder, removed only when empty. One use per write.
 */
public final class SpeciesFiles {

    private static final Logger LOG = LoggerFactory.getLogger(SpeciesFiles.class);

    private final Path data;
    private final FileMoves moves = new FileMoves();
    private Path oldFolder;
    /** The new folder, when the rename is the one to make it (an undo then removes it if empty). */
    private Path madeFolder;

    /** @param data the library's data folder */
    public SpeciesFiles(Path data) {
        this.data = data;
    }

    private record Planned(long id, boolean live, String fromPath, String oldFileName, String fileName, String toPath) {
    }

    /**
     * Moves the photos of {@code taxonId} from {@code oldFolder} to {@code newFolder} (relative to the data folder, ending
     * in {@code /}), renaming those named after {@code oldName}, and updates their rows with one {@code photo.move}
     * entry per live photo. Every target is checked first: one taken (a file there, or two photos given names equal
     * without case) is 409 {@code file_exists} and nothing moves; a move that fails throws, and {@link #undo} puts back
     * the files moved.
     */
    public void rename(Connection c, String taxonId, String oldFolder, String newFolder, String oldName, String newName,
                       Instant now) throws SQLException {
        this.oldFolder = PhotoFiles.resolve(data, oldFolder);
        Path target = PhotoFiles.resolve(data, newFolder);
        madeFolder = Files.exists(target) ? null : target;
        List<Planned> planned = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, deleted_at IS NULL, rel_path, file_name, number FROM image"
                + " WHERE taxon_id = ? ORDER BY id")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fileName = rs.getString(4);
                    int number = rs.getInt(5);
                    String renamed = rs.wasNull() ? fileName : renamed(fileName, number, oldName, newName);
                    boolean live = rs.getInt(2) == 1;
                    planned.add(new Planned(rs.getLong(1), live, rs.getString(3), fileName, renamed,
                            live ? newFolder + renamed : null));
                }
            }
        }
        Set<String> taken = new HashSet<>();
        for (Planned p : planned) {
            if (p.live() && (!taken.add(p.fileName().toLowerCase(Locale.ROOT)) || Files.exists(PhotoFiles.resolve(data, p.toPath()))
                    || PhotoRows.livePathTaken(c, p.toPath()))) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, "file_exists",
                        "A file named " + p.fileName() + " is already in the species folder.");
            }
        }
        List<Map<String, ?>> logged = new ArrayList<>();
        try (PreparedStatement live = c.prepareStatement("UPDATE image SET rel_path = ?, file_name = ? WHERE id = ?");
             PreparedStatement deleted = c.prepareStatement("UPDATE image SET file_name = ? WHERE id = ?")) {
            for (Planned p : planned) {
                if (!p.live()) {
                    deleted.setString(1, p.fileName());
                    deleted.setLong(2, p.id());
                    deleted.executeUpdate();
                    continue;
                }
                try {
                    moves.move(PhotoFiles.resolve(data, p.fromPath()), PhotoFiles.resolve(data, p.toPath()), p.oldFileName());
                } catch (AtlasException e) {
                    if (e.kind() != AtlasException.Kind.NOT_FOUND) {
                        throw e;
                    }
                    // Its record is out of step with the folder: the startup rescan puts that right.
                    throw AtlasException.notFound("The file of " + p.oldFileName() + " can no longer be found. Restart"
                            + " Butterfly Atlas so it can check the library, then try again.");
                }
                live.setString(1, p.toPath());
                live.setString(2, p.fileName());
                live.setLong(3, p.id());
                live.executeUpdate();
                logged.add(ChangeLog.payload("id", p.id(), "taxonId", taxonId, "fromPath", p.fromPath(), "toPath", p.toPath()));
            }
        }
        ChangeLog.recordAll(c, now, ChangeLog.MOVE, logged);
    }

    /** {@code fileName} with the new name when it is the app's {@code <oldName>_NN.ext}, else as it is. */
    static String renamed(String fileName, int number, String oldName, String newName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName;
        }
        String extension = fileName.substring(dot + 1);
        return fileName.equals(FileNames.photoName(oldName, number, extension))
                ? FileNames.photoName(newName, number, extension) : fileName;
    }

    /** Puts back the files moved (the write did not commit), and removes the new folder if it made it and it is empty. */
    public void undo() {
        moves.undo();
        if (madeFolder != null) {
            removeIfEmpty(madeFolder);
        }
    }

    /** After the commit: the old folder is removed when nothing is left in it (a file the user put there keeps it). */
    public void removeOldFolder() {
        if (oldFolder != null) {
            removeIfEmpty(oldFolder);
        }
    }

    /**
     * Moves one photo file to a path that must not exist yet: never over an existing file, atomically where the file
     * system can (one volume); the folders it goes into are made (a taxonomy update's moves, 5b contract §3).
     */
    public static void moveFile(Path from, Path to) throws IOException {
        PhotoFiles.move(from, to);
    }

    /** Removes the species folder {@code folder} (relative to the data folder {@code data}) when it is empty. */
    public static void removeIfEmpty(Path data, String folder) {
        removeIfEmpty(PhotoFiles.resolve(data, folder));
    }

    private static void removeIfEmpty(Path folder) {
        try {
            Files.deleteIfExists(folder);
        } catch (DirectoryNotEmptyException e) {
            LOG.info("Kept {}: it still holds files", folder);
        } catch (NoSuchFileException e) {
            // gone already
        } catch (IOException e) {
            LOG.warn("Could not remove {}", folder, e);
        }
    }
}
