package com.butterflyatlas.app.photos.consumers;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.photos.PhotoConsumer;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionsTest {

    @TempDir
    Path dir;

    @Test
    void writesTheDisplayedSizeAndOrientationOfEachPhoto() {
        Database db = TestBundles.fixtureLibrary(dir).database();
        long a = TestBundles.addImage(db, "A5");
        long b = TestBundles.addImage(db, "A5");
        Dimensions dimensions = new Dimensions();

        db.write(tx -> {
            dimensions.apply(List.of(
                    new PhotoConsumer.Subject(a, PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of(), Map.of()), null),
                    new PhotoConsumer.Subject(b, PhotoFacts.of(PhotoFormat.JPEG, 20, 40, 6, null, null, Map.of(), Map.of()), null)),
                    tx);
            return null;
        });

        assertThat(dimensions.key()).isEqualTo("dimensions");
        assertThat(dimensions.version()).isEqualTo(1);
        assertThat(dimensions.kind()).isEqualTo(PhotoConsumer.Kind.FACTS);
        List<String> sizes = db.read(tx -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT width, height, orientation FROM image ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getInt(1) + "x" + rs.getInt(2) + " o" + rs.getInt(3));
                }
            }
            return rows;
        });
        assertThat(sizes).containsExactly("40x20 o1", "20x40 o6");
    }
}
