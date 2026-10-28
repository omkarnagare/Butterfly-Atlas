package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Match;
import com.butterflyatlas.app.updates.UpdateViews.MatchCustom;
import com.butterflyatlas.app.updates.UpdateViews.MatchOfficial;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Custom species that now match an official species (5b contract §5, design D15): offered, merged, declined. */
class CustomMatchesTest {

    private static final String SANKARA = "images/Nymphalidae/Danainae/Danaini/Neptis/neptis_SANKARA_[A21]/";

    @TempDir
    Path dir;

    private UpdateFixture f;
    private Photo tagged;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated(); // U1 Neptis sankara (Broad-banded Sailer) with a photo
        tagged = f.photos.add("U1", new ByteArrayInputStream(TestImages.jpeg(50, 40, 777)), "image/jpeg", null,
                List.of("male")).photo();
        Photo deleted = f.add("U1");
        f.photos.delete(List.of(deleted.id()));
        f.photos.setCover("U1", tagged.id());
        f.custom.add("A9", "Papilio clytius", null); // U2: will be a bundled synonym of Papilio polytes
        f.custom.add("A9", "Papilio gulnihal", null); // U3: a search word of Papilio demoleus, never a match
        f.sql("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A13', 'Papilio gulnihal', 'user', 't')");
        f.sql("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('U1', 'sailer', 'user', 't')");
        f.sql("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES ('U1', 'word', 'sailer')");
    }

    /** v1 → v2: Neptis sankara added (A21, in a new genus A20) and Papilio clytius a synonym of Papilio polytes. */
    private static Bundle v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").add("A20", "A3", "genus", "Neptis", null)
                .add("A21", "A20", "species", "neptis SANKARA", null).synonyms("A12", "Papilio clytius").build();
    }

    private Updates updates(Bundle... chain) throws InterruptedException {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(chain), f.clock);
        updates.startup();
        return updates;
    }

    private static Update run(Updates updates, Runnable start) throws InterruptedException {
        start.run();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        return updates.update();
    }

    @Test
    void afterAnUpdateACustomSpeciesNamedAsAnOfficialSpeciesOrItsSynonymIsOffered() throws Exception {
        Updates updates = updates(UpdateFixture.v1(), v2());

        Update done = run(updates, updates::apply);

        List<Match> expected = List.of(
                new Match(new MatchCustom("U1", "Neptis sankara", "Broad-banded Sailer", 2),
                        new MatchOfficial("A21", "neptis SANKARA", null, "Nymphalidae"), "2026-02-01"),
                new Match(new MatchCustom("U2", "Papilio clytius", null, 0),
                        new MatchOfficial("A12", "Papilio polytes", "Common Mormon", "Papilionidae"), "2026-02-01"));
        assertThat(updates.matches()).isEqualTo(expected);
        assertThat(done.result().matches()).isEqualTo(expected);
        assertThat(f.rows("SELECT custom_id, official_id, offered_version, state FROM custom_match ORDER BY custom_id"))
                .containsExactly("U1|A21|2026-02-01|open", "U2|A12|2026-02-01|open");
    }

    @Test
    void mergingMovesThePhotosWordsCoverAndImportItemsAndDeletesTheCustomSpecies() throws Exception {
        f.sql("INSERT INTO import_run (root_path, state, created_at, updated_at, filings) VALUES ('/photos', 'open', 't', 't', 0)");
        f.sql("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, seq, taxon_id)"
                + " VALUES (1, 1, 'a.jpg', 10, 't', 'read', 1, 'U1')");
        Updates updates = updates(UpdateFixture.v1(), v2());
        run(updates, updates::apply);

        Update merged = run(updates, () -> updates.merge("U1", "A21"));

        assertThat(merged.state()).isEqualTo("done");
        assertThat(merged.kind()).isEqualTo("match");
        assertThat(merged.result().photos()).isEqualTo(2);
        assertThat(merged.result().matches()).extracting(m -> m.custom().id()).containsExactly("U2");
        assertThat(f.rows("SELECT id, taxon_id, rel_path, file_name, number, deleted_at IS NOT NULL FROM image"
                + " WHERE taxon_id IN ('U1', 'A21') ORDER BY id")).extracting(r -> r.substring(r.indexOf('|') + 1)).containsExactly(
                "A21|" + SANKARA + "neptis_SANKARA_01.jpg|neptis_SANKARA_01.jpg|1|0",
                "A21|" + SANKARA + "neptis_SANKARA_02.jpg|neptis_SANKARA_02.jpg|2|0",
                "A21|images/Custom_additions/Neptis_sankara_[U1]/Neptis_sankara_03.jpg|neptis_SANKARA_03.jpg|3|1");
        assertThat(f.file(SANKARA + "neptis_SANKARA_02.jpg")).isRegularFile();
        assertThat(f.file("images/Custom_additions/Neptis_sankara_[U1]")).doesNotExist();
        assertThat(f.rows("SELECT k.key FROM image_tag t JOIN tag k ON k.id = t.tag_id WHERE t.image_id = " + tagged.id()))
                .containsExactly("male");
        assertThat(f.rows("SELECT cover_image_id, next_photo_number FROM taxon WHERE id = 'A21'"))
                .containsExactly(tagged.id() + "|4");
        assertThat(f.rows("SELECT name FROM taxon_synonym WHERE taxon_id = 'A21' AND source = 'user' ORDER BY name"))
                .as("its common name and search words; not its name, the official one's").containsExactly("Broad-banded Sailer",
                        "sailer");
        assertThat(f.rows("SELECT text FROM taxon_fts WHERE taxon_id = 'A21' AND kind = 'word' ORDER BY text"))
                .containsExactly("Broad-banded Sailer", "sailer");
        assertThat(f.rows("SELECT COUNT(*) FROM taxon WHERE id = 'U1'")).containsExactly("0");
        assertThat(f.rows("SELECT COUNT(*) FROM taxon_fts WHERE taxon_id = 'U1'")).containsExactly("0");
        assertThat(f.rows("SELECT taxon_id FROM import_item WHERE id = 1")).containsExactly("A21");
        assertThat(f.rows("SELECT payload_json FROM change_log WHERE kind = 'custom.merge'")).containsExactly(
                "{\"customId\":\"U1\",\"officialId\":\"A21\",\"name\":\"Neptis sankara\",\"from\":\"2026-02-01\","
                        + "\"to\":\"2026-02-01\",\"update\":2,\"photos\":2,\"moved\":2,\"keptFolders\":[],\"notRemoved\":[],"
                        + "\"leftBehind\":[],\"splits\":[],\"splitsMerged\":[],\"matches\":[{\"custom\":{\"id\":\"U2\",\"name\":\"Papilio clytius\","
                        + "\"common\":null,\"photos\":0},\"official\":{\"id\":\"A12\",\"name\":\"Papilio polytes\","
                        + "\"common\":\"Common Mormon\",\"family\":\"Papilionidae\"},\"offeredVersion\":\"2026-02-01\"}],\"notes\":[]}");
        assertThat(f.rows("SELECT state FROM custom_match WHERE custom_id = 'U1'")).containsExactly("merged");
        assertThat(f.rows("PRAGMA foreign_key_check")).isEmpty();
        assertThat(f.custom.add("A1", "Neptis soma", null).id()).as("U1 is never given again").isEqualTo("U4");
        List<Photo> restored = f.photos.restore(List.of(Long.parseLong(f.rows("SELECT id FROM image WHERE deleted_at IS NOT NULL"
                + " AND taxon_id = 'A21'").getFirst())));
        assertThat(restored.getFirst().relPath()).isEqualTo(SANKARA + "neptis_SANKARA_03.jpg");
    }

    @Test
    void aDeclinedMatchIsOfferedAgainAfterTheNextUpdate() throws Exception {
        Updates updates = updates(UpdateFixture.v1(), v2());
        run(updates, updates::apply);

        updates.decline("U2", "A12");

        assertThat(updates.matches()).extracting(m -> m.custom().id()).containsExactly("U1");
        assertThatThrownBy(() -> updates.merge("U2", "A12")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("no_match"));
        assertThatThrownBy(() -> updates.decline("U2", "A12")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));

        Bundle v3 = UpdateBundles.derive(v2(), "2026-03-01").rename("A6", "Danaus melanippus").build();
        Updates next = updates(UpdateFixture.v1(), v2(), v3);
        run(next, next::apply);

        assertThat(f.rows("SELECT custom_id, official_id, offered_version, state FROM custom_match ORDER BY custom_id,"
                + " offered_version")).containsExactly("U1|A21|2026-02-01|open", "U2|A12|2026-02-01|declined",
                "U2|A12|2026-03-01|open");
        assertThat(next.matches()).extracting(m -> m.custom().id() + " " + m.offeredVersion())
                .containsExactly("U1 2026-02-01", "U2 2026-03-01");
    }

    @Test
    void aMergeCutShortByTheAppClosingContinuesAtTheNextStart() throws Exception {
        Updates updates = updates(UpdateFixture.v1(), v2());
        run(updates, updates::apply);
        Updates[] closing = new Updates[1];
        closing[0] = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2()), f.clock, (from, to) -> {
            closing[0].beginClose();
            com.butterflyatlas.app.photos.SpeciesFiles.moveFile(from, to);
        }, path -> Long.MAX_VALUE);
        closing[0].startup();

        run(closing[0], () -> closing[0].merge("U1", "A21"));

        assertThat(f.rows("SELECT kind, state, stage, done, total FROM taxonomy_update WHERE kind = 'match'"))
                .containsExactly("match|running|moves|1|2");
        Updates next = updates(UpdateFixture.v1(), v2());
        assertThat(next.status().orElseThrow().kind()).isEqualTo("match");
        next.awaitContinuation();
        Update done = next.update();
        assertThat(done.state()).isEqualTo("done");
        assertThat(done.steps()).containsExactly("2026-02-01");
        assertThat(f.rows("SELECT COUNT(*) FROM image WHERE taxon_id = 'A21' AND deleted_at IS NULL")).containsExactly("2");
        assertThat(f.rows("SELECT COUNT(*) FROM taxon WHERE id = 'U1'")).containsExactly("0");
    }

    @Test
    void aMatchWhoseOfficialSpeciesLeftTheListIsWithdrawn() throws Exception {
        Updates updates = updates(UpdateFixture.v1(), v2());
        run(updates, updates::apply);
        f.sql("UPDATE taxon SET active = 0 WHERE id = 'A21'");

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> updates.merge("U1", "A21"), () -> updates.decline("U1", "A21"))) {
            assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
                assertThat(e.code()).isEqualTo("match_withdrawn");
                assertThat(e.getMessage()).isEqualTo("neptis SANKARA is no longer on the official list, so this match was"
                        + " withdrawn.");
            });
        }
        assertThat(f.rows("SELECT COUNT(*) FROM taxonomy_update WHERE kind = 'match'")).containsExactly("0");
        assertThat(updates.matches()).extracting(m -> m.custom().id()).containsExactly("U2");
    }

    @Test
    void aMergeDoneWhileANewerUpdateIsPendingLeavesThatUpdateOnOffer() throws Exception {
        Updates first = updates(UpdateFixture.v1(), v2());
        run(first, first::apply);
        Bundle v3 = UpdateBundles.derive(v2(), "2026-03-01").rename("A6", "Danaus melanippus").build();
        Updates updates = updates(UpdateFixture.v1(), v2(), v3);
        updates.later();

        Update merged = run(updates, () -> updates.merge("U1", "A21"));

        assertThat(merged.state()).as("the pending update is still on offer").isEqualTo("pending");
        assertThat(merged.later()).isTrue();
        assertThat(merged.steps()).containsExactly("2026-03-01");
        assertThat(merged.last().photos()).as("the merge's result").isEqualTo(2);
        assertThat(updates.status().orElseThrow().state()).isEqualTo("pending");
        run(updates, updates::apply);
        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(updates.update().kind()).isEqualTo("bundle");
    }

    @Test
    void aMergeNeedsBothSpeciesAndAnOpenMatch() throws Exception {
        Updates updates = updates(UpdateFixture.v1(), v2());
        run(updates, updates::apply);

        assertThatThrownBy(() -> updates.merge("U9", "A21")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("There is no custom species U9 in your atlas.");
        });
        assertThatThrownBy(() -> updates.merge("U1", "A99")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> updates.merge("U1", "A12")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("no_match");
            assertThat(e.getMessage()).isEqualTo("U1 is not offered for merging into A12.");
        });
        assertThat(f.rows("SELECT COUNT(*) FROM taxonomy_update WHERE kind = 'match'")).containsExactly("0");
    }
}
