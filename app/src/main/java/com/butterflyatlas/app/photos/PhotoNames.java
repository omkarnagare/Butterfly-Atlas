package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.taxa.TaxonSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;

/** Photo file names in a species folder: the next free {@code <Genus>_<epithet>_NN.ext}, and the species' next number. */
final class PhotoNames {

    private PhotoNames() {
    }

    /** A photo's name, number (null for a file copied in by hand) and place. */
    record Named(Integer number, String fileName, String relPath, Path file) {
    }

    /**
     * The first free {@code <Genus>_<epithet>_NN.ext} in the species folder from {@code number} on:
     * free means no live photo has that path and no file of that name sits there (copied in by hand).
     */
    static Named freeName(Connection c, Path data, String taxonId, String extension, int number) throws SQLException {
        return freeName(c, TaxonSnapshot.load(c), data, taxonId, extension, number);
    }

    /** {@link #freeName(Connection, Path, String, String, int)} with the taxa already loaded (moves and restores of many photos). */
    static Named freeName(Connection c, TaxonSnapshot snapshot, Path data, String taxonId, String extension, int number)
            throws SQLException {
        TaxonSnapshot.Row row = snapshot.find(taxonId)
                .orElseThrow(() -> AtlasException.noTaxon(taxonId));
        String folder = snapshot.folder(row);
        for (int n = number; ; n++) {
            String fileName = FileNames.photoName(row.name(), n, extension);
            String relPath = folder + fileName;
            Path file = PhotoFiles.resolve(data, relPath);
            if (!PhotoRows.livePathTaken(c, relPath) && !Files.exists(file)) {
                return new Named(n, fileName, relPath, file);
            }
        }
    }

    static int nextNumber(Connection c, String taxonId) throws SQLException {
        return PhotoRows.count(c, "SELECT next_photo_number FROM taxon WHERE id = ?", ps -> ps.setString(1, taxonId));
    }

    static void setNextNumber(Connection c, String taxonId, int next) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE taxon SET next_photo_number = max(next_photo_number, ?) WHERE id = ?")) {
            ps.setInt(1, next);
            ps.setString(2, taxonId);
            ps.executeUpdate();
        }
    }

    /** The extension the app would give this file: {@code jpg} for {@code IMG_1.JPEG}. */
    static String extension(String fileName) {
        return PhotoFormat.fromFileName(fileName).map(PhotoFormat::extension).orElseGet(() -> {
            int dot = fileName.lastIndexOf('.');
            return dot < 0 ? "jpg" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        });
    }
}
