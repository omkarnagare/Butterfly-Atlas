package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A species' cover, read for all species at once ({@link PhotoQueries#covers}, for Home and a taxon's cards) and for one
 * ({@link PhotoRows#coverId}, the species page), is the rule's: the chosen cover while it is live and still the species',
 * else the newest adult photo, else the newest, ties by the higher id. {@link #RULE} is that rule as the app first wrote
 * it (one sort of all the species' photos), kept here as the reference the faster queries are checked against, over a
 * random library full of ties, deleted photos, chosen covers that were deleted or moved away, and tags of every kind.
 */
class CoversTest {

    @TempDir
    Path dir;

    /** The cover rule as first written: the reference. */
    private static final String RULE = "SELECT coalesce("
            + "(SELECT c.id FROM image c WHERE c.id = t.cover_image_id AND c.taxon_id = t.id AND c.deleted_at IS NULL),"
            + " (SELECT c.id FROM image c WHERE c.taxon_id = t.id AND c.deleted_at IS NULL"
            + " ORDER BY EXISTS (SELECT 1 FROM image_tag ct JOIN tag k ON k.id = ct.tag_id"
            + " WHERE ct.image_id = c.id AND k.key IN ('dsf', 'female', 'male', 'under_wing', 'upper_wing', 'wsf')) DESC,"
            + " c.added_at DESC, c.id DESC LIMIT 1)) FROM taxon t WHERE t.id = ?";

    @Test
    void everyCoverReadAtOnceIsTheCoverOfThatOneSpecies() {
        Library library = TestBundles.fixtureLibrary(dir);
        Random random = new Random(7);
        library.database().write(c -> {
            List<String> species = strings(c, "SELECT id FROM taxon WHERE rank = 'species' ORDER BY id");
            List<Long> tags = new ArrayList<>();
            for (String id : strings(c, "SELECT id FROM tag ORDER BY id")) {
                tags.add(Long.parseLong(id));
            }
            try (PreparedStatement image = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number,"
                    + " file_size, modified_at, sha256, added_at, source, deleted_at, deleted_rel_path)"
                    + " VALUES (?, ?, ?, ?, 1, '2026-10-09T08:00:00Z', ?, ?, 'app', ?, ?)");
                 PreparedStatement tag = c.prepareStatement("INSERT OR IGNORE INTO image_tag (image_id, tag_id) VALUES (?, ?)")) {
                for (int i = 1; i <= 3_000; i++) {
                    // few distinct times, so many photos of a species share their added_at
                    String taxon = species.get(random.nextInt(species.size()));
                    boolean deleted = random.nextInt(8) == 0;
                    image.setString(1, taxon);
                    image.setString(2, "images/x/p" + i + ".jpg");
                    image.setString(3, "p" + i + ".jpg");
                    image.setInt(4, i);
                    image.setString(5, String.format("%064x", i));
                    image.setString(6, "2026-10-0" + (1 + random.nextInt(3)) + "T08:00:00Z");
                    image.setString(7, deleted ? "2026-10-10T00:00:00Z" : null);
                    image.setString(8, deleted ? ".deleted/p" + i + ".jpg" : null);
                    image.executeUpdate();
                    for (int t = random.nextInt(3); t > 0; t--) {
                        tag.setLong(1, i);
                        tag.setLong(2, tags.get(random.nextInt(tags.size())));
                        tag.executeUpdate();
                    }
                }
            }
            // A chosen cover for half the species: any photo, its own or another species', live or deleted.
            try (PreparedStatement cover = c.prepareStatement("UPDATE taxon SET cover_image_id = ? WHERE id = ?")) {
                for (String id : species) {
                    if (random.nextBoolean()) {
                        cover.setLong(1, 1 + random.nextInt(3_000));
                        cover.setString(2, id);
                        cover.executeUpdate();
                    }
                }
            }
            return null;
        });

        library.database().read(c -> {
            Map<String, Long> expected = new HashMap<>();
            for (String id : strings(c, "SELECT id FROM taxon WHERE rank = 'species'")) {
                Long cover = rule(c, id);
                assertThat(PhotoRows.coverId(c, id)).as(id).isEqualTo(cover);
                if (cover != null) {
                    expected.put(id, cover);
                }
            }
            assertThat(expected).hasSizeGreaterThan(5);
            assertThat(ids(PhotoQueries.covers(c))).isEqualTo(expected);
            for (String parent : strings(c, "SELECT DISTINCT parent_id FROM taxon WHERE rank = 'species'")) {
                Map<String, Long> under = new HashMap<>();
                for (String id : strings(c, "SELECT id FROM taxon WHERE rank = 'species' AND parent_id = '" + parent + "'")) {
                    if (expected.containsKey(id)) {
                        under.put(id, expected.get(id));
                    }
                }
                assertThat(ids(PhotoQueries.covers(c, parent))).as(parent).isEqualTo(under);
            }
            Map<String, PhotoQueries.Cover> all = PhotoQueries.covers(c);
            for (Map.Entry<String, PhotoQueries.Cover> e : all.entrySet()) {
                assertThat(e.getValue().v()).isEqualTo(PhotoQueries.cacheKey("2026-10-09T08:00:00Z", 1));
            }
            return null;
        });
    }

    @Test
    void theAutomaticCoverSkipsPhotosThatCannotBeShown() {
        Library library = TestBundles.fixtureLibrary(dir);
        library.database().write(c -> {
            try (PreparedStatement image = c.prepareStatement("INSERT INTO image (id, taxon_id, rel_path, file_name,"
                    + " file_size, modified_at, sha256, added_at, source, file_type, width, height)"
                    + " VALUES (?, ?, ?, ?, 1, '2026-10-09T08:00:00Z', ?, ?, 'app', ?, ?, ?)")) {
                Object[][] rows = {
                        {1L, "A5", "2026-10-01T08:00:00Z", "jpeg", 40, 30}, // readable, oldest
                        {2L, "A5", "2026-10-02T08:00:00Z", "jpeg", 40, 30}, // its thumbnail failed
                        {3L, "A5", "2026-10-03T08:00:00Z", "raw", null, null}, // a RAW without a preview
                        {4L, "A6", "2026-10-01T08:00:00Z", "raw", null, null}, // the species' only photo
                };
                for (Object[] row : rows) {
                    image.setLong(1, (Long) row[0]);
                    image.setString(2, (String) row[1]);
                    image.setString(3, "images/x/p" + row[0]);
                    image.setString(4, "p" + row[0]);
                    image.setString(5, String.format("%064x", (Long) row[0]));
                    image.setString(6, (String) row[2]);
                    image.setString(7, (String) row[3]);
                    image.setObject(8, row[4]);
                    image.setObject(9, row[5]);
                    image.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image_processing (image_id, consumer, version,"
                    + " status, error, done_at, attempts) VALUES (2, '" + Thumbnails.CONSUMER + "', " + Thumbnails.VERSION
                    + ", 'failed', 'x', 't', 1)")) {
                ps.executeUpdate();
            }
            return null;
        });

        library.database().read(c -> {
            assertThat(PhotoRows.coverId(c, "A5")).as("fixes J12").isEqualTo(1L);
            assertThat(ids(PhotoQueries.covers(c))).containsEntry("A5", 1L).doesNotContainKey("A6");
            assertThat(PhotoRows.coverId(c, "A6")).as("none it can show").isNull();
            return null;
        });
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET cover_image_id = 3 WHERE id = 'A5'")) {
                return ps.executeUpdate();
            }
        });
        library.database().read(c -> {
            assertThat(PhotoRows.coverId(c, "A5")).as("the user's own choice stands").isEqualTo(3L);
            return null;
        });
    }

    private static Long rule(Connection c, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RULE)) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                return rs.wasNull() ? null : id;
            }
        }
    }

    private static Map<String, Long> ids(Map<String, PhotoQueries.Cover> covers) {
        Map<String, Long> ids = new HashMap<>();
        covers.forEach((taxon, cover) -> ids.put(taxon, cover.id()));
        return ids;
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
