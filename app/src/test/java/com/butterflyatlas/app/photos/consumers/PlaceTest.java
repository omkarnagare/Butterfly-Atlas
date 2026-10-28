package com.butterflyatlas.app.photos.consumers;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.photos.PhotoConsumer;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceTest {

    @TempDir
    Path dir;

    private static PhotoConsumer.Subject at(long id, Double lat, Double lon) {
        PhotoFacts.Gps gps = lat == null ? null : new PhotoFacts.Gps(lat, lon, null);
        return new PhotoConsumer.Subject(id, PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, gps, Map.of(), Map.of()), null);
    }

    private static List<String> places(Database db) {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, coalesce(place_id, '-'), coalesce(place_source, '-')"
                    + " FROM image ORDER BY id"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3));
                }
            }
            return rows;
        });
    }

    private static void exec(Database db, String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void aPhotoWithGpsGetsTheLeafPlaceItWasTakenInFromGps() {
        Database db = TestBundles.fixtureLibrary(dir).database();
        long inAlpha = TestBundles.addImage(db, "A5");
        long offshore = TestBundles.addImage(db, "A5");
        long atSea = TestBundles.addImage(db, "A5");
        long noGps = TestBundles.addImage(db, "A5");
        Place place = new Place(TestPlaces.bundles().index());

        db.write(tx -> {
            place.apply(List.of(at(inAlpha, 18.5, 73.5), at(offshore, 18.5, 72.99), at(atSea, 15.0, 65.0),
                    at(noGps, null, null)), tx);
            return null;
        });

        assertThat(place.key()).isEqualTo("place");
        assertThat(place.version()).isEqualTo(1);
        assertThat(place.kind()).isEqualTo(PhotoConsumer.Kind.FACTS);
        assertThat(places(db)).containsExactly(inAlpha + " P4 gps", offshore + " P4 gps", atSea + " - -", noGps + " - -");
    }

    @Test
    void aGpsPlaceFollowsItsPhotosGpsButAUsersPlaceIsNeverWritten() {
        Database db = TestBundles.fixtureLibrary(dir).database();
        long moved = TestBundles.addImage(db, "A5");
        long gpsGone = TestBundles.addImage(db, "A5");
        long setByUser = TestBundles.addImage(db, "A5");
        long removedByUser = TestBundles.addImage(db, "A5");
        exec(db, "UPDATE image SET place_id = 'P4', place_source = 'gps' WHERE id IN (" + moved + ", " + gpsGone + ")");
        exec(db, "UPDATE image SET place_id = 'P8', place_source = 'user' WHERE id = " + setByUser);
        exec(db, "UPDATE image SET place_id = NULL, place_source = 'user' WHERE id = " + removedByUser);
        Place place = new Place(TestPlaces.bundles().index());

        db.write(tx -> {
            place.apply(List.of(at(moved, 18.5, 74.5), at(gpsGone, null, null), at(setByUser, 18.5, 74.5),
                    at(removedByUser, 18.5, 74.5)), tx);
            return null;
        });

        assertThat(places(db)).containsExactly(moved + " P5 gps", gpsGone + " - -", setByUser + " P8 user",
                removedByUser + " - user");
    }
}
