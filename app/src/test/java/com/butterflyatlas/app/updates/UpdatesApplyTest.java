package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Applying an update on a populated library with real files (5b contract §3): plan, moves, switch-over, chained steps. */
class UpdatesApplyTest {

    private static final String DANAUS = "images/Nymphalidae/Danainae/Danaini/Danaus/";
    private static final String PAPILIO = "images/Papilionidae/Papilioninae/Papilio/";

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
    }

    private Updates updates(Bundle... chain) {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(chain), f.clock);
        updates.startup();
        return updates;
    }

    private static Update applied(Updates updates) throws InterruptedException {
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        return updates.update();
    }

    /** Every live photo's file is where its row says, in its species' folder. */
    private void everyPhotoIsWhereItsRowSays() {
        Map<String, String> folders = f.db.read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            Map<String, String> byId = new java.util.HashMap<>();
            f.rows("SELECT DISTINCT taxon_id FROM image").forEach(id -> byId.put(id, snapshot.folder(snapshot.find(id).orElseThrow())));
            return byId;
        });
        for (String row : f.rows("SELECT taxon_id, rel_path, file_name FROM image WHERE deleted_at IS NULL")) {
            String[] r = row.split("\\|");
            assertThat(r[1]).as(row).isEqualTo(folders.get(r[0]) + r[2]);
            assertThat(f.file(r[1])).as(row).isRegularFile();
        }
    }

    @Test
    void theUsualUpdateMovesRenamesMergesSplitsAddsAndRemovesWithTheFilesFollowing() throws Exception {
        f.populated();
        String genutia = DANAUS + "Danaus_genutia_[A6]/";
        String melanippus = DANAUS + "Danaus_melanippus_[A6]/";
        byte[] first = Files.readAllBytes(f.file(genutia + "Danaus_genutia_01.jpg"));
        f.sql("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A14', 'mime', 'user', 't')");
        f.sql("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES ('A14', 'word', 'mime')");

        Update done = applied(updates(UpdateFixture.v1(), UpdateFixture.v2()));

        assertThat(done.state()).isEqualTo("done");
        assertThat(done.kind()).isEqualTo("bundle");
        assertThat(done.result().photos()).as("5 moved, 3 split, 1 of a removed species").isEqualTo(9);
        assertThat(done.result().keptFolders()).isEmpty();
        assertThat(done.progress()).isNull();
        everyPhotoIsWhereItsRowSays();
        assertThat(Files.readAllBytes(f.file(melanippus + "Danaus_melanippus_01.jpg"))).isEqualTo(first);
        assertThat(f.file(melanippus + "Danaus_melanippus_02.jpg")).exists();
        assertThat(f.file(genutia)).as("emptied: removed").doesNotExist();
        assertThat(f.file("images/Nymphalidae/Danainae/Tirumala/Tirumala_limniace_[A8]/Tirumala_limniace_01.jpg")).exists();
        assertThat(f.file("images/Nymphalidae/Danainae/Danaini/Tirumala")).doesNotExist();
        assertThat(f.file(DANAUS)).as("still holds Danaus chrysippus").isDirectory();
        assertThat(f.file(PAPILIO + "Papilio_agestor_[A15]/Papilio_agestor_01.jpg")).exists();
        assertThat(f.file(PAPILIO + "Papilio_agestor_[A15]/Papilio_agestor_02.jpg")).exists();
        assertThat(f.file(PAPILIO + "Papilio_clytia_[A14]")).doesNotExist();
        assertThat(f.file(PAPILIO + "Papilio_demoleus_[A13]/Papilio_demoleus_01.jpg")).as("removed: stays").exists();
        assertThat(f.rows("SELECT taxon_id, file_name, number FROM image WHERE deleted_at IS NULL ORDER BY id")).containsExactly(
                "A5|Danaus_chrysippus_01.jpg|1", "A5|Danaus_chrysippus_02.jpg|2", "A5|Danaus_chrysippus_03.jpg|3",
                "A6|Danaus_melanippus_01.jpg|1", "A6|Danaus_melanippus_02.jpg|2", "A8|Tirumala_limniace_01.jpg|1",
                "A12|Papilio_polytes_01.jpg|1", "A13|Papilio_demoleus_01.jpg|1", "A15|Papilio_agestor_01.jpg|1",
                "A15|Papilio_agestor_02.jpg|2", "U1|Neptis_sankara_01.jpg|1");
        assertThat(f.rows("SELECT id, parent_id, name, active, folder_name, ancestor_path, next_photo_number FROM taxon"
                + " WHERE id IN ('A6', 'A7', 'A8', 'A13', 'A14', 'A15', 'A16', 'A17') ORDER BY CAST(substr(id, 2) AS INTEGER)"))
                .containsExactly(
                        "A6|A4|Danaus melanippus|1|Danaus_melanippus_[A6]|/G:butterflies/A1/A2/A3/A4/A6/|3",
                        "A7|A2|Tirumala|1|Tirumala|/G:butterflies/A1/A2/A7/|1",
                        "A8|A7|Tirumala limniace|1|Tirumala_limniace_[A8]|/G:butterflies/A1/A2/A7/A8/|2",
                        "A13|A11|Papilio demoleus|0|Papilio_demoleus_[A13]|/G:butterflies/A9/A10/A11/A13/|2",
                        "A14|A11|Papilio clytia|0|Papilio_clytia_[A14]|/G:butterflies/A9/A10/A11/A14/|3",
                        "A15|A11|Papilio agestor|1|Papilio_agestor_[A15]|/G:butterflies/A9/A10/A11/A15/|3",
                        "A16|A4|Danaus petilia|1|Danaus_petilia_[A16]|/G:butterflies/A1/A2/A3/A4/A16/|1",
                        "A17|A11|Papilio memnon|1|Papilio_memnon_[A17]|/G:butterflies/A9/A10/A11/A17/|1");
        assertThat(f.rows("SELECT s.image_id, s.taxon_id, s.version FROM split_photo s JOIN image i ON i.id = s.image_id"
                + " ORDER BY s.image_id")).extracting(r -> r.substring(r.indexOf('|') + 1))
                .containsExactly("A5|2026-02-01", "A5|2026-02-01", "A5|2026-02-01");
        assertThat(f.rows("SELECT taxon_id, kind, text FROM taxon_fts WHERE taxon_id IN ('A6', 'A14', 'A15')"
                + " ORDER BY taxon_id, kind, text")).containsExactly("A14|common|Common Mime", "A14|name|Papilio clytia",
                "A15|common|Tawny Mime", "A15|name|Papilio agestor", "A15|word|mime", "A6|common|Striped Tiger",
                "A6|name|Danaus melanippus", "A6|synonym|Salatura genutia");
        assertThat(f.rows("SELECT taxon_id, name, source FROM taxon_synonym WHERE taxon_id IN ('A14', 'A15')"))
                .containsExactly("A15|mime|user");
        assertThat(f.rows("SELECT bundle_version FROM taxonomy_state ORDER BY bundle_version"))
                .containsExactly("2026-01-01", "2026-02-01");
        assertThat(f.rows("SELECT kind, COUNT(*) FROM change_log WHERE payload_json LIKE '%\"version\":\"2026-02-01\"%'"
                + " OR payload_json LIKE '%\"update\":1%' GROUP BY kind ORDER BY kind")).containsExactly(
                "photo.move|5", "taxon.add|3", "taxon.merge|1", "taxon.move|1", "taxon.remove|1", "taxon.rename|1",
                "taxon.split|1", "taxonomy.done|1", "taxonomy.step|1");
        assertThat(f.rows("SELECT payload_json FROM change_log WHERE kind IN ('taxon.rename', 'taxon.merge', 'taxon.split',"
                + " 'taxonomy.step') ORDER BY id")).containsExactly(
                "{\"id\":\"A6\",\"from\":\"Danaus genutia\",\"to\":\"Danaus melanippus\",\"version\":\"2026-02-01\"}",
                "{\"ids\":[\"A14\"],\"from\":[\"Papilio clytia\"],\"to\":\"A15\",\"version\":\"2026-02-01\"}",
                "{\"id\":\"A5\",\"from\":\"Danaus chrysippus\",\"to\":[\"A5\",\"A16\"],\"version\":\"2026-02-01\","
                        + "\"photos\":3}",
                "{\"from\":\"2026-01-01\",\"to\":\"2026-02-01\",\"update\":1,\"keptFolders\":[],\"notRemoved\":[]}");
        assertThat(f.rows("SELECT state, stage, step_version, total, done, finished_at IS NOT NULL FROM taxonomy_update"))
                .containsExactly("done|switch|2026-02-01|5|5|1");
        assertThat(updates(UpdateFixture.v1(), UpdateFixture.v2()).update().state()).as("nothing left").isEqualTo("none");
    }

    @Test
    void photosInRecentlyDeletedFollowARenameAndAMergeAndRestoreIntoTheNewFolder() throws Exception {
        f.add("A6", 2);
        Photo genutia = f.add("A6");
        f.add("A14", 2);
        Photo clytia = f.add("A14");
        f.photos.delete(List.of(genutia.id(), clytia.id()));

        applied(updates(UpdateFixture.v1(), UpdateFixture.v2()));

        assertThat(f.rows("SELECT id, taxon_id, file_name, number FROM image WHERE deleted_at IS NOT NULL ORDER BY id"))
                .containsExactly(genutia.id() + "|A6|Danaus_melanippus_03.jpg|3", clytia.id() + "|A15|Papilio_agestor_03.jpg|3");
        assertThat(f.rows("SELECT next_photo_number FROM taxon WHERE id = 'A15'")).containsExactly("4");
        assertThat(f.rows("SELECT COUNT(*) FROM taxonomy_move WHERE image_id IN (" + genutia.id() + ", " + clytia.id() + ")"))
                .as("no file to move").containsExactly("0");
        List<Photo> restored = f.photos.restore(List.of(genutia.id(), clytia.id()));
        assertThat(restored).extracting(Photo::relPath).containsExactly(DANAUS + "Danaus_melanippus_[A6]/Danaus_melanippus_03.jpg",
                PAPILIO + "Papilio_agestor_[A15]/Papilio_agestor_03.jpg");
        everyPhotoIsWhereItsRowSays();
    }

    @Test
    void aFolderHoldingAFileOfTheUsersIsKeptAndListed() throws Exception {
        f.add("A14", 2);
        Files.writeString(f.file(PAPILIO + "Papilio_clytia_[A14]/notes.txt"), "seen at Tamhini");

        Update done = applied(updates(UpdateFixture.v1(), UpdateFixture.v2()));

        assertThat(done.result().keptFolders()).containsExactly(PAPILIO + "Papilio_clytia_[A14]/");
        assertThat(f.file(PAPILIO + "Papilio_clytia_[A14]/notes.txt")).hasContent("seen at Tamhini");
    }

    @Test
    void skippedReleasesAreAppliedStepByStepAndARemovedSpeciesComesBack() throws Exception {
        f.populated();
        Bundle v2 = UpdateFixture.v2();
        Bundle v3 = UpdateBundles.derive(v2, "2026-03-01").rename("A6", "Danaus genutius").readd(UpdateFixture.v1Taxon("A13"))
                .rename("A7", "Tirumalia").build();

        Update done = applied(updates(UpdateFixture.v1(), v2, v3));

        assertThat(done.steps()).containsExactly("2026-02-01", "2026-03-01");
        assertThat(done.from()).isEqualTo("2026-01-01");
        assertThat(done.to()).isEqualTo("2026-03-01");
        everyPhotoIsWhereItsRowSays();
        assertThat(f.file(DANAUS + "Danaus_genutius_[A6]/Danaus_genutius_02.jpg")).exists();
        assertThat(f.file("images/Nymphalidae/Danainae/Tirumalia/Tirumala_limniace_[A8]/Tirumala_limniace_01.jpg")).exists();
        assertThat(f.file("images/Nymphalidae/Danainae/Tirumala")).doesNotExist();
        assertThat(f.rows("SELECT active FROM taxon WHERE id = 'A13'")).containsExactly("1");
        assertThat(f.rows("SELECT bundle_version FROM taxonomy_state ORDER BY bundle_version"))
                .containsExactly("2026-01-01", "2026-02-01", "2026-03-01");
        assertThat(f.rows("SELECT payload_json FROM change_log WHERE kind IN ('taxonomy.step', 'taxon.reactivate',"
                + " 'taxonomy.done') ORDER BY id")).containsExactly(
                "{\"from\":\"2026-01-01\",\"to\":\"2026-02-01\",\"update\":1,\"keptFolders\":[],\"notRemoved\":[]}",
                "{\"id\":\"A13\",\"from\":null,\"to\":\"Papilio demoleus\",\"version\":\"2026-03-01\"}",
                "{\"from\":\"2026-02-01\",\"to\":\"2026-03-01\",\"update\":1,\"keptFolders\":[],\"notRemoved\":[]}",
                "{\"from\":\"2026-01-01\",\"to\":\"2026-03-01\",\"update\":1,\"photos\":9,\"moved\":8,\"keptFolders\":[],"
                        + "\"notRemoved\":[],\"leftBehind\":[],\"splits\":[{\"id\":\"A5\",\"name\":\"Danaus chrysippus\","
                        + "\"common\":\"Plain Tiger\",\"family\":\"Nymphalidae\",\"photos\":3,\"version\":\"2026-02-01\","
                        + "\"targets\":[{\"id\":\"A5\",\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\"},"
                        + "{\"id\":\"A16\",\"name\":\"Danaus petilia\",\"common\":null}]}],\"splitsMerged\":[],"
                        + "\"matches\":[],\"notes\":[]}");
        assertThat(f.rows("SELECT step_version, COUNT(*) FROM taxonomy_move GROUP BY step_version ORDER BY step_version"))
                .containsExactly("2026-02-01|5", "2026-03-01|3");
        assertThat(f.rows("SELECT total, done FROM taxonomy_update")).containsExactly("8|8");
        assertThat(done.result().photos()).as("as the summary counts them").isEqualTo(9);
    }

    @Test
    void aHigherRankMergeMovesTheLibraryOnlySpeciesAndACustomSpeciesFollowsItsMergedFamily() throws Exception {
        Photo gautama = addInactiveSpeciesWithAPhoto();
        f.add("A8");
        Photo polytes = f.add("A12");
        f.custom.add("A9", "Papilio sankara", null);
        f.add("U1");

        applied(updates(UpdateFixture.v1(), UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").merge("A4", "A7")
                .merge("A1", "A9").build()));

        everyPhotoIsWhereItsRowSays();
        assertThat(f.rows("SELECT rel_path FROM image WHERE id = " + gautama.id()))
                .containsExactly(DANAUS + "Tirumala_gautama_[A99]/Tirumala_gautama_01.jpg");
        assertThat(f.rows("SELECT parent_id, active FROM taxon WHERE id = 'A99'")).containsExactly("A4|0");
        assertThat(f.rows("SELECT family_id FROM taxon WHERE id = 'U1'")).containsExactly("A1");
        assertThat(f.rows("SELECT rel_path FROM image WHERE id = " + polytes.id()))
                .containsExactly("images/Nymphalidae/Papilioninae/Papilio/Papilio_polytes_[A12]/Papilio_polytes_01.jpg");
        assertThat(f.file("images/Papilionidae")).as("emptied and removed").doesNotExist();
    }

    /** A species the library still has but the list dropped earlier (inactive), under Tirumala, with a photo. */
    private Photo addInactiveSpeciesWithAPhoto() {
        f.sql("INSERT INTO taxon (id, parent_id, rank, name, group_key, source, ancestor_path, folder_name, active, sort_key)"
                + " VALUES ('A99', 'A7', 'species', 'Tirumala gautama', 'butterflies', 'official', '/G:butterflies/A1/A2/A3/A7/A99/',"
                + " 'Tirumala_gautama_[A99]', 1, 'tirumala gautama')");
        Photo photo = f.add("A99");
        f.sql("UPDATE taxon SET active = 0 WHERE id = 'A99'");
        return photo;
    }

    @Test
    void theWorkRunsOnItsOwnThreadAndCustomIdsDoNotCountTheOfficialTaxaAdded() throws Exception {
        f.populated();
        List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock,
                (from, to) -> {
                    threads.add(Thread.currentThread().getName());
                    com.butterflyatlas.app.photos.SpeciesFiles.moveFile(from, to);
                }, path -> Long.MAX_VALUE);
        updates.startup();

        applied(updates);

        assertThat(threads).hasSize(5).containsOnly("atlas-taxonomy");
        assertThat(f.custom.add("A1", "Neptis soma", null).id()).as("A15, A16, A17 were added: still U2").isEqualTo("U2");
    }

    @Test
    void applyIsRefusedWithNothingPendingOrWhileOneRuns() throws Exception {
        Updates none = updates(UpdateFixture.v1());
        assertThatThrownBy(none::apply).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("no_update");
            assertThat(e.getMessage()).isEqualTo("There is no taxonomy update to apply.");
        });

        Updates updates = updates(UpdateFixture.v1(), UpdateFixture.v2());
        assertThat(updates.update().state()).isEqualTo("pending");
        applied(updates);
        assertThatThrownBy(updates::apply).as("done: nothing left").isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("no_update"));
        f.sql("INSERT INTO taxonomy_update (kind, from_version, to_version, step_version, stage, state, started_at)"
                + " VALUES ('match', '2026-02-01', '2026-02-01', '2026-02-01', 'moves', 'paused', 't')");
        Bundle v3 = UpdateBundles.derive(UpdateFixture.v2(), "2026-03-01").build();
        Updates another = updates(UpdateFixture.v1(), UpdateFixture.v2(), v3);
        assertThatThrownBy(another::apply).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("update_running"));
    }
}
