package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.custom.CustomSpecies;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.UpdateBundles;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A populated library on the 14-taxon fixture (version 2026-01-01) with real photo files, for update tests: photos are
 * added through {@link PhotoStore#add}, so their files, names and numbers are the app's own.
 */
final class UpdateFixture {

    final Path dir;
    final TestClock clock = new TestClock();
    final Library library;
    final Database db;
    final PhotoStore photos;
    final TaxonService taxa;
    final CustomSpecies custom;
    private int seed;

    UpdateFixture(Path dir) {
        this.dir = dir;
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        db = library.database();
        photos = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, id -> { });
        taxa = new TaxonService(() -> library.database());
        custom = new CustomSpecies(() -> library, taxa, clock);
    }

    /** The fixture bundle (v1). */
    static Bundle v1() {
        return UpdateBundles.fixture();
    }

    /** A taxon of the fixture bundle as it is there. */
    static BundleTaxon v1Taxon(String id) {
        return v1().taxa().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    /** Adds a photo (a real JPEG, different bytes each time) to a species. */
    Photo add(String taxonId) {
        clock.advance(Duration.ofMinutes(1));
        seed++;
        // A size of its own for each photo: never the same bytes twice (a duplicate would not be added).
        byte[] jpeg = TestImages.jpeg(32 + seed % 64, 24 + seed / 64 % 32, seed);
        return photos.add(taxonId, new ByteArrayInputStream(jpeg), "image/jpeg", null, List.of()).photo();
    }

    /** Adds {@code count} photos to a species; returns them. */
    List<Photo> add(String taxonId, int count) {
        List<Photo> added = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            added.add(add(taxonId));
        }
        return added;
    }

    Path file(String relPath) {
        Path path = library.folder();
        for (String part : relPath.split("/")) {
            if (!part.isEmpty()) {
                path = path.resolve(part);
            }
        }
        return path;
    }

    List<String> rows(String sql) {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        row.append(i > 1 ? "|" : "").append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
            return rows;
        });
    }

    void sql(String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * The usual populated library: Danaus chrysippus (A5) 3 live photos and 1 in Recently deleted, Danaus genutia (A6) 2,
     * Tirumala limniace (A8) 1, Papilio polytes (A12) 1, Papilio demoleus (A13) 1, Papilio clytia (A14) 2, and the custom
     * species Neptis sankara (U1, Nymphalidae) 1. Live photos 11.
     */
    UpdateFixture populated() {
        add("A5", 4);
        photos.delete(List.of(rows("SELECT max(id) FROM image WHERE taxon_id = 'A5'").getFirst()).stream()
                .map(Long::parseLong).toList());
        add("A6", 2);
        add("A8");
        add("A12");
        add("A13");
        add("A14", 2);
        custom.add("A1", "Neptis sankara", "Broad-banded Sailer");
        add("U1");
        return this;
    }

    /**
     * The usual v2 of the fixture: Danaus genutia renamed Danaus melanippus; the genus Tirumala moved from the tribe
     * Danaini to the subfamily Danainae; Papilio clytia merged into the new Papilio agestor (A15); Danaus chrysippus split
     * into itself and the new Danaus petilia (A16); Papilio demoleus removed; Papilio memnon (A17) added.
     */
    static Bundle v2() {
        return UpdateBundles.derive(v1(), "2026-02-01")
                .rename("A6", "Danaus melanippus")
                .move("A7", "A2")
                .add("A15", "A11", "species", "Papilio agestor", "Tawny Mime")
                .merge("A15", "A14")
                .add("A16", "A4", "species", "Danaus petilia", null)
                .split("A5", "A5", "A16")
                .remove("A13")
                .add("A17", "A11", "species", "Papilio memnon", "Great Mormon")
                .build();
    }
}
