package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactConsumersTest {

    @TempDir
    Path dir;

    private final ProcessingLedger ledger = new ProcessingLedger(new TestClock());

    /** A fact consumer that writes nothing, or fails as told. */
    private record Named(String key, int version, RuntimeException failure) implements PhotoConsumer {

        @Override
        public Kind kind() {
            return Kind.FACTS;
        }

        @Override
        public void apply(List<Subject> batch, Connection tx) {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static List<String> rows(Database db) {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT image_id || ' ' || consumer || ' v' || version || ' '"
                    + " || status FROM image_processing ORDER BY image_id, consumer"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }

    private static PhotoConsumer.Subject subject(long id) {
        return new PhotoConsumer.Subject(id, PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of(), Map.of()), null);
    }

    @Test
    void everyConsumerProcessesThePhotosAndIsMarkedDoneInTheCallersTransaction() {
        Database db = TestBundles.fixtureLibrary(dir).database();
        long a = TestBundles.addImage(db, "A5");
        long b = TestBundles.addImage(db, "A5");
        FactConsumers facts = new FactConsumers(List.of(new Dimensions(), new Named("place", 2, null)), ledger);

        db.write(tx -> {
            facts.applyAll(tx, List.of(subject(a), subject(b)));
            return null;
        });

        assertThat(rows(db)).containsExactly(a + " dimensions v1 done", a + " place v2 done", b + " dimensions v1 done",
                b + " place v2 done");
        String size = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT width || 'x' || height FROM image WHERE id = " + a);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(size).isEqualTo("40x20");
    }

    @Test
    void aConsumerThatFailsIsLeftPendingForTheCatchUpWithoutFailingTheRest() {
        Database db = TestBundles.fixtureLibrary(dir).database();
        long a = TestBundles.addImage(db, "A5");
        FactConsumers facts = new FactConsumers(List.of(new Named("place", 1, new IllegalStateException("no boundaries")),
                new Dimensions()), ledger);

        db.write(tx -> {
            facts.applyAll(tx, List.of(subject(a)));
            return null;
        });

        assertThat(rows(db)).as("no row for the consumer that failed").containsExactly(a + " dimensions v1 done");
        List<Long> pending = db.read(c -> ledger.pending(c, "place", 1, 0, 10));
        assertThat(pending).as("left for the catch-up").containsExactly(a);
    }

    @Test
    void aConsumerThatKeepsFailingLogsItsStackTraceOnceThenALineAPhoto() throws Exception {
        Database db = TestBundles.fixtureLibrary(dir).database();
        long a = TestBundles.addImage(db, "A5");
        long b = TestBundles.addImage(db, "A5");
        // A key of its own: the stack trace is logged once per consumer for the whole run of the app (here, of the tests).
        FactConsumers facts = new FactConsumers(List.of(new Named("place-log-test", 1,
                new IllegalStateException("no boundaries"))), ledger);
        PrintStream err = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            for (long id : List.of(a, b, a)) {
                db.write(tx -> {
                    facts.applyAll(tx, List.of(subject(id)));
                    return null;
                });
            }
        } finally {
            System.setErr(err);
        }

        String log = out.toString(StandardCharsets.UTF_8);
        assertThat(log.lines().filter(line -> line.contains("place-log-test")).count()).as(log).isEqualTo(3);
        assertThat(log.lines().filter(line -> line.startsWith("java.lang.IllegalStateException")).count())
                .as("one stack trace").isEqualTo(1);
        assertThat(log.lines().filter(line -> line.contains("no boundaries")).count()).as("every line says why").isEqualTo(3);
    }

    @Test
    void onlyFactConsumersAreAccepted() {
        assertThatThrownBy(() -> new FactConsumers(List.of(new PhotoConsumer() {
            @Override
            public String key() {
                return "thumbnail";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Kind kind() {
                return Kind.PIXELS;
            }

            @Override
            public void apply(List<Subject> batch, Connection tx) {
            }
        }), ledger)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thumbnail");
    }
}
