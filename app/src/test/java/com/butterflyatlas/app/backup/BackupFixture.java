package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The fixture library at {@code home/Atlas} with photos as real files (under {@code images/}, recorded straight into
 * {@code image}), a destination folder {@code home/Backup}, and {@link Backups} over them with a free space the test sets
 * ({@link #free}) and dates in UTC (the clock: 2026-10-11, which a test moves).
 */
final class BackupFixture {

    final Path home;
    final Library library;
    final Database db;
    final Path dest;
    final TestClock clock = new TestClock();
    final AtomicLong free = new AtomicLong(Long.MAX_VALUE);
    final Backups backups;

    BackupFixture(Path home) {
        this.home = home;
        library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        db = library.database();
        dest = home.resolve("Backup");
        try {
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        backups = new Backups(() -> library, clock, ZoneOffset.UTC, p -> free.get());
    }

    /** A live photo of {@code taxonId} stored as {@code images/<taxon>/<fileName>} with these bytes. */
    long photo(String taxonId, String fileName, byte[] bytes) {
        String rel = "images/" + taxonId + "/" + fileName;
        Path file = library.folder().resolve(rel);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, file_size,"
                    + " modified_at, sha256, added_at, source) VALUES (?, ?, ?, ?, '2026-10-09T08:00:00Z', ?,"
                    + " '2026-10-09T08:00:00Z', 'app')", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, taxonId);
                ps.setString(2, rel);
                ps.setString(3, fileName);
                ps.setLong(4, bytes.length);
                ps.setString(5, rel);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** {@code count} photos of {@code A5} named p1.jpg, p2.jpg, … each with its own bytes. */
    void photos(int count) {
        for (int i = 1; i <= count; i++) {
            photo("A5", "p" + i + ".jpg", ("photo number " + i + " ").repeat(40).getBytes());
        }
    }

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

    /** The library's own database file. */
    Path dbFile() {
        return db.file();
    }

    /** The backup's folder of the run. */
    Path folder(Backups.Backup backup) {
        return Path.of(backup.path());
    }

    /** Waits until the backup is in this state; true when it got there. */
    boolean await(long id, String state) throws InterruptedException {
        return await(() -> state.equals(backups.store().run(id).state()));
    }

    static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    Backups.Request request(String what) {
        return new Backups.Request(what, dest.toString());
    }
}
