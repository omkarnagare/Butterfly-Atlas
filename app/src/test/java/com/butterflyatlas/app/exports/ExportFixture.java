package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.imaging.CommonsMetadataStripper;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixture library at {@code home/Atlas} with photos as real files (under {@code images/}, recorded straight into
 * {@code image}), a destination folder {@code home/Backup}, and {@link Exports} over them with a free space the test
 * sets ({@link #free}) and dates in UTC (the fixed clock: 2026-10-09).
 */
final class ExportFixture {

    private static final Pattern NUMBER = Pattern.compile("_(\\d+)\\.[a-z]+$");

    final Path home;
    final Library library;
    final Database db;
    final Path dest;
    final AtomicLong free = new AtomicLong(Long.MAX_VALUE);
    final Exports exports;

    ExportFixture(Path home) {
        this(home, new CommonsMetadataStripper());
    }

    /** With this location remover (tests give one that fails). */
    ExportFixture(Path home, MetadataStripper stripper) {
        this.home = home;
        library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        db = library.database();
        dest = home.resolve("Backup");
        try {
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        exports = new Exports(() -> library, new FilterQuery(library::database), stripper, TestBundles.CLOCK, ZoneOffset.UTC,
                p -> free.get());
    }

    /** A live photo of {@code taxonId} stored as {@code fileName} with these bytes (at {@code placeId}, null: unknown). */
    long photo(String taxonId, String fileName, byte[] bytes, String placeId) {
        String rel = "images/" + taxonId + "/" + fileName;
        Path file = library.folder().resolve(rel);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher number = NUMBER.matcher(fileName);
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number, file_size,"
                    + " modified_at, sha256, added_at, source, place_id) VALUES (?, ?, ?, ?, ?, '2026-10-09T08:00:00Z', ?,"
                    + " '2026-10-09T08:00:00Z', 'app', ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, taxonId);
                ps.setString(2, rel);
                ps.setString(3, fileName);
                ps.setObject(4, number.find() ? Integer.valueOf(number.group(1)) : null);
                ps.setLong(5, bytes.length);
                ps.setString(6, rel);
                ps.setString(7, placeId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** The library file of a photo. */
    Path file(long imageId) {
        String rel = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM image WHERE id = ?")) {
                ps.setLong(1, imageId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        return library.folder().resolve(rel);
    }

    void sql(String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A start into {@link #dest} with the defaults. */
    Exports.Request request(String filter, String what) {
        return new Exports.Request(filter, what, dest.toString(), null, null, null);
    }
}
