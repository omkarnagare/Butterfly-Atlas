package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.FileNames;
import com.butterflyatlas.app.taxonomy.BundleLoader;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** The 14-taxon fixture bundle and helpers to build test libraries from it. */
public final class TestBundles {

    public static final String FIXTURE = "fixture-bundle.json";
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);

    private static final AtomicInteger IMAGES = new AtomicInteger();

    private TestBundles() {
    }

    public static byte[] fixtureBytes() {
        try (InputStream in = TestBundles.class.getResourceAsStream("/taxonomy-fixture/" + FIXTURE)) {
            return Objects.requireNonNull(in, "missing test resource /taxonomy-fixture/" + FIXTURE).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bundles holding just the fixture, with a correct checksum list. */
    public static TaxonomyBundles fixture() {
        byte[] bytes = fixtureBytes();
        byte[] sums = (TaxonomyBundles.sha256(bytes) + "  " + FIXTURE + "\n").getBytes(StandardCharsets.UTF_8);
        return TaxonomyBundles.load(name -> switch (name) {
            case TaxonomyBundles.CHECKSUMS -> new ByteArrayInputStream(sums);
            case FIXTURE -> new ByteArrayInputStream(bytes);
            default -> null;
        });
    }

    /** A new database in {@code dir} with the fixture loaded. */
    public static Database fixtureDatabase(Path dir) {
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        BundleLoader.load(db, fixture().newest(), CLOCK);
        return db;
    }

    /** The fixture library (data folder and database, with the fixture places of {@link TestPlaces}) in {@code dir}. */
    public static Library fixtureLibrary(Path dir) {
        return Library.open(dir, fixture(), TestPlaces.bundles(), CLOCK);
    }

    /**
     * Records one photo of {@code taxonId} without a file on disk, for count and visibility tests.
     * Generated rel_paths depend on test order; do not assert on them. Returns the image id.
     */
    public static long addImage(Database db, String taxonId) {
        return db.write(c -> {
            int n = IMAGES.incrementAndGet();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, file_size,"
                    + " modified_at, sha256, added_at, source) VALUES (?, ?, ?, 1, '2026-10-09T08:00:00Z', ?,"
                    + " '2026-10-09T08:00:00Z', 'app')", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, taxonId);
                ps.setString(2, "images/test/photo_" + n + ".jpg");
                ps.setString(3, "photo_" + n + ".jpg");
                ps.setString(4, String.format("%064x", n));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Puts a photo recorded by {@link #addImage} into Recently deleted. */
    public static void markDeleted(Database db, long imageId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE image SET deleted_at = '2026-10-09T08:00:00Z', deleted_rel_path = '.deleted/x.jpg' WHERE id = ?")) {
                ps.setLong(1, imageId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Tags a photo recorded by {@link #addImage}. */
    public static void tagImage(Database db, long imageId, String... keys) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO image_tag (image_id, tag_id) SELECT ?, id FROM tag WHERE key = ?")) {
                for (String key : keys) {
                    ps.setLong(1, imageId);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Marks a taxon as removed by a later bundle (D1). */
    public static void retire(Database db, String taxonId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET active = 0 WHERE id = ?")) {
                ps.setString(1, taxonId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Adds a manual species under "Custom additions" (D5), with no family. */
    public static void addManualSpecies(Database db, String id, String name) {
        addManualSpecies(db, id, name, null);
    }

    /** Adds a custom species of the family {@code familyId} under "Custom additions" (5a contract §2), as a row only. */
    public static void addManualSpecies(Database db, String id, String name, String familyId) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key,"
                    + " source, ancestor_path, folder_name, active, sort_key, family_id)"
                    + " VALUES (?, 'U:butterflies', 'species', ?, NULL, 'butterflies', 'manual', ?, ?, 1, lower(?), ?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, "/G:butterflies/U:butterflies/" + id + "/");
                ps.setString(4, FileNames.safe(name + " [" + id + "]"));
                ps.setString(5, name);
                ps.setString(6, familyId);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
