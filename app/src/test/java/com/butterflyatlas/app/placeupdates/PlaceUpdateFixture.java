package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.imports.ImportPlaceChoices;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoPlaces;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.UserPlaces;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.UpdatePlaces;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A populated library on the fixture places (version 2026-10-13) with real photo files, for place update tests: photos are
 * added through {@link PhotoStore#add} (real JPEGs, with GPS when given, so the place consumer looks their place up as
 * the app does), hand-placed through {@link PhotoPlaces}, user places made through {@link UserPlaces}.
 */
final class PlaceUpdateFixture {

    /** Where the fixture's places are (lat, lon). */
    static final double[] ALPHA_WEST = {18.5, 73.25};
    static final double[] ALPHA_EAST = {18.5, 73.75};
    static final double[] ISLET = {18.5, 76.5};
    static final double[] BETA = {18.5, 74.5};
    static final double[] LAKELAND = {18.1, 76.1};

    final Path dir;
    final TestClock clock = new TestClock();
    final Library library;
    final Database db;
    final PhotoStore photos;
    final PhotoPlaces photoPlaces;
    final UserPlaces userPlaces;
    private int seed;

    /** The ids of the usual populated library ({@link #populated}). */
    long alphaWest;
    long alphaEast;
    long alphaByHand;
    long islet;
    long isletByHand;
    long isletDeleted;
    long beta;
    long westfarByHand;
    long nowhere;
    long inMyAlphaWest;
    long inTamhini;
    String myAlphaWest;
    String tamhini;
    String coast;
    long run;

    PlaceUpdateFixture(Path dir) {
        this(dir, TestPlaces.bundles());
    }

    PlaceUpdateFixture(Path dir, PlaceBundles places) {
        this.dir = dir;
        library = Library.open(dir.resolve("Atlas"), TestBundles.fixture(), places, clock);
        db = library.database();
        photos = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, id -> { },
                List.of(new Place(places.index())));
        photoPlaces = new PhotoPlaces(() -> library, clock);
        userPlaces = new UserPlaces(() -> library.database(), clock, new PlaceSearch(() -> library.database()),
                new ImportPlaceChoices());
    }

    /** Adds a photo of a species (a real JPEG, different bytes each time), taken at {@code at} (lat, lon) or without GPS. */
    long add(String taxonId, double[] at) {
        clock.advance(Duration.ofMinutes(1));
        seed++;
        byte[] jpeg = TestImages.jpeg(32 + seed % 64, 24 + seed / 64 % 32, seed);
        if (at != null) {
            jpeg = TestExif.camera().gps(at[0], at[1], 10).jpeg(jpeg);
        }
        return photos.add(taxonId, new ByteArrayInputStream(jpeg), "image/jpeg", null, List.of()).photo().id();
    }

    /** Sets the photos' place by hand. */
    void place(String placeId, long... ids) {
        clock.advance(Duration.ofMinutes(1));
        List<Long> list = new ArrayList<>();
        for (long id : ids) {
            list.add(id);
        }
        photoPlaces.place(list, placeId, false);
    }

    /**
     * The usual populated library: in Alpha (P4) two GPS photos (west and east of 73.5° E) and one placed by hand; in
     * Islet (P7) a GPS photo, one placed by hand and a GPS photo in Recently deleted; a GPS photo in Beta (P5); one placed
     * by hand in Westfar (P9); one with no place; the user districts Alpha West (with a photo placed by hand) and Tamhini
     * (with one) under Northstate (P2) and Coast under Leafstate (P3); an open import with items whose GPS place is Islet
     * and Beta and whose chosen place is Alpha and the user's Alpha West; the recent places Islet, Lakeland (newer) and
     * Alpha West.
     */
    PlaceUpdateFixture populated() {
        alphaWest = add("A5", ALPHA_WEST);
        alphaEast = add("A5", ALPHA_EAST);
        alphaByHand = add("A6", ALPHA_WEST);
        place("P4", alphaByHand);
        islet = add("A5", ISLET);
        isletByHand = add("A6", null);
        place("P7", isletByHand);
        isletDeleted = add("A5", ISLET);
        photos.delete(List.of(isletDeleted));
        beta = add("A8", BETA);
        westfarByHand = add("A12", null);
        place("P9", westfarByHand);
        nowhere = add("A13", null);
        myAlphaWest = userPlaces.add("Alpha West", "P2").id();
        inMyAlphaWest = add("A14", null);
        place(myAlphaWest, inMyAlphaWest);
        tamhini = userPlaces.add("Tamhini", "P2").id();
        inTamhini = add("A14", null);
        place(tamhini, inTamhini);
        coast = userPlaces.add("Coast", "P3").id();
        sql("INSERT INTO import_run (id, root_path, state, created_at, updated_at, next_seq) VALUES (1, '/photos/2024', 'open',"
                + " '2026-10-12T00:00:00Z', '2026-10-12T00:00:00Z', 5)");
        run = 1;
        sql("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, seq, gps_place_id,"
                + " user_place_id, user_place_set) VALUES"
                + " (1, 1, 'a.jpg', 1, '2026-10-02T08:00:00Z', 'read', 1, 'P7', NULL, 0),"
                + " (2, 1, 'b.jpg', 1, '2026-10-02T08:00:00Z', 'read', 2, NULL, 'P4', 1),"
                + " (3, 1, 'c.jpg', 1, '2026-10-02T08:00:00Z', 'read', 3, NULL, '" + myAlphaWest + "', 1),"
                + " (4, 1, 'd.jpg', 1, '2026-10-02T08:00:00Z', 'read', 4, 'P5', NULL, 0)");
        sql("DELETE FROM place_recent");
        sql("INSERT INTO place_recent (place_id, used_at) VALUES ('P7', '2026-10-12T01:00:00Z'),"
                + " ('P6', '2026-10-12T02:00:00Z'), ('" + myAlphaWest + "', '2026-10-12T03:00:00Z')");
        return this;
    }

    /** The fixture list and its usual next one ({@link UpdatePlaces#fixtureNext}), as shipped. */
    static PlaceBundles next() {
        return UpdatePlaces.bundles(UpdatePlaces.fixture(), UpdatePlaces.fixtureNext());
    }

    /** Applies one step of {@code chain} (from its list {@code step - 1} to {@code step}, 1-based) in one transaction. */
    PlaceStep.Summary step(PlaceBundles chain, int step) {
        return db.write(c -> new PlaceStep(clock).apply(c, chain.all().get(step - 2), chain.all().get(step - 1)));
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

    String one(String sql) {
        return rows(sql).getFirst();
    }

    void sql(String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** {@code id|place_id|place_source} of every photo, Recently deleted included. */
    List<String> places() {
        return rows("SELECT id, place_id, place_source FROM image ORDER BY id");
    }
}
