package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One step of a place update on a populated library (5c contract §4). */
class PlaceStepTest {

    @TempDir
    Path dir;

    /** No photo has lost its place and every place a photo or import item names is still in the library. */
    private static void nothingLost(PlaceUpdateFixture f, int placed) {
        assertThat(f.one("SELECT COUNT(*) FROM image WHERE place_id IS NOT NULL")).isEqualTo(String.valueOf(placed));
        assertThat(f.rows("SELECT i.id FROM image i LEFT JOIN place p ON p.id = i.place_id WHERE i.place_id IS NOT NULL"
                + " AND p.id IS NULL")).as("photos in a place the library lacks").isEmpty();
        assertThat(f.rows("SELECT i.id FROM import_item i LEFT JOIN place p ON p.id = coalesce(i.user_place_id,"
                + " i.gps_place_id) WHERE coalesce(i.user_place_id, i.gps_place_id) IS NOT NULL AND p.id IS NULL")).isEmpty();
        assertThat(f.rows("SELECT id FROM place p WHERE ancestor_path <> coalesce((SELECT q.ancestor_path FROM place q"
                + " WHERE q.id = p.parent_id), '/') || p.id || '/'")).as("every ancestor_path follows its parent").isEmpty();
        assertThat(f.rows("PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void theUsualStepMergesRenamesMovesSplitsRetiresAndAddsWithoutLosingAPlace() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        int placed = Integer.parseInt(f.one("SELECT COUNT(*) FROM image WHERE place_id IS NOT NULL"));
        int bundled = Integer.parseInt(f.one("SELECT COUNT(*) FROM place WHERE source = 'bundled'"));
        assertThat(f.places()).contains(f.alphaWest + "|P4|gps", f.alphaEast + "|P4|gps", f.islet + "|P7|gps",
                f.isletDeleted + "|P7|gps", f.beta + "|P5|gps");

        PlaceStep.Summary summary = f.step(PlaceUpdateFixture.next(), 2);

        assertThat(f.places()).containsExactlyInAnyOrder(
                f.alphaWest + "|P4|gps", f.alphaEast + "|P4|gps", f.alphaByHand + "|P4|user",   // split: left as they were
                f.islet + "|P6|gps", f.isletByHand + "|P6|user", f.isletDeleted + "|P6|gps",  // merged, source kept
                f.beta + "|P5|gps", f.westfarByHand + "|P9|user", f.nowhere + "|null|null",
                f.inMyAlphaWest + "|P11|user",                                                  // P4: into the new Alpha West
                f.inTamhini + "|" + f.tamhini + "|user");
        nothingLost(f, placed);
        assertThat(f.one("SELECT COUNT(*) FROM place WHERE source = 'bundled'")).as("bundled rows are never deleted")
                .isEqualTo(String.valueOf(bundled + 3));
        assertThat(f.rows("SELECT id, parent_id, name, active, replaced_by, ancestor_path FROM place WHERE id IN ('P4', 'P5',"
                + " 'P6', 'P7', 'P9', 'P10', 'P11', 'P12') ORDER BY CAST(substr(id, 2) AS INTEGER)")).containsExactly(
                "P4|P2|Alpha|0|null|/P1/P2/P4/",
                "P5|P10|Betania|1|null|/P1/P10/P5/",
                "P6|P2|Lakeland|1|null|/P1/P2/P6/",
                "P7|P2|Islet|0|P6|/P1/P2/P7/",
                "P9|null|Westfar|0|null|/P9/",
                "P10|P1|Southstate|1|null|/P1/P10/",
                "P11|P2|Alpha West|1|null|/P1/P2/P11/",
                "P12|P2|Alpha East|1|null|/P1/P2/P12/");
        assertThat(f.rows("SELECT id FROM place WHERE source = 'user' ORDER BY id")).as("only the clashing user place went")
                .containsExactly(f.tamhini, f.coast);
        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id IN ('P5', 'P6', 'P11') ORDER BY 1, 2"))
                .containsExactly("P5|Beta", "P6|Islet");
        assertThat(f.rows("SELECT id, gps_place_id, user_place_id, seq FROM import_item ORDER BY id")).containsExactly(
                "1|P6|null|5", "2|null|P4|2", "3|null|P11|6", "4|P5|null|4");
        assertThat(f.one("SELECT next_seq FROM import_run WHERE id = 1")).isEqualTo("7");
        assertThat(f.rows("SELECT place_id, used_at FROM place_recent ORDER BY place_id")).as("Islet's entry joins the newer"
                + " Lakeland one; Alpha West's goes to the official Alpha West").containsExactly(
                "P11|2026-10-12T03:00:00Z", "P6|2026-10-12T02:00:00Z");
        assertThat(f.rows("SELECT bundle_version FROM place_state ORDER BY rowid")).containsExactly("2026-10-13", "2026-11-01");

        assertThat(summary.counts()).isEqualTo(new PlaceStep.Counts(1, 1, 1, 1, 1, 3));
        assertThat(summary.items()).extracting(i -> i.type() + " " + i.from() + " " + i.names().from() + " → "
                + i.names().to() + " " + i.photos()).containsExactly(
                "merge [P7] [Islet] → [Lakeland] 2",
                "split [P4] [Alpha] → [Alpha West, Alpha East] 3",
                "retire [P9] [Westfar] → [] 1",
                "rename [P5] [Beta] → [Betania] 1",
                "move [P5] [Beta] → [Betania] 1");
        assertThat(summary.mine()).containsExactly(new PlaceStep.Mine(f.myAlphaWest, "Alpha West", "P11", 1, false));
        assertThat(summary.touches()).isTrue();
        assertThat(f.rows("SELECT version, from_version, touches FROM place_update")).containsExactly("2026-11-01|2026-10-13|1");
        assertThat(PlaceStep.summary(f.one("SELECT summary_json FROM place_update"))).isEqualTo(summary);

        assertThat(f.rows("SELECT kind, payload_json FROM change_log WHERE kind LIKE 'bundled.%' OR kind IN ('place.step',"
                + " 'place.merged') ORDER BY id")).containsExactly(
                "bundled.merge|{\"from\":[\"P7\"],\"to\":\"P6\",\"photos\":[" + f.islet + "," + f.isletByHand + ","
                        + f.isletDeleted + "],\"importItems\":1,\"version\":\"2026-11-01\"}",
                "bundled.split|{\"from\":\"P4\",\"to\":[\"P11\",\"P12\"],\"version\":\"2026-11-01\"}",
                "bundled.retire|{\"id\":\"P9\",\"version\":\"2026-11-01\"}",
                "place.merged|{\"userId\":\"" + f.myAlphaWest + "\",\"name\":\"Alpha West\",\"into\":\"P11\",\"photos\":["
                        + f.inMyAlphaWest + "],\"importItems\":1,\"version\":\"2026-11-01\"}",
                "bundled.add|{\"ids\":[\"P10\",\"P11\",\"P12\"],\"version\":\"2026-11-01\"}",
                "bundled.rename|{\"id\":\"P5\",\"from\":\"Beta\",\"to\":\"Betania\",\"version\":\"2026-11-01\"}",
                "bundled.move|{\"id\":\"P5\",\"fromParent\":\"P2\",\"toParent\":\"P10\",\"version\":\"2026-11-01\"}",
                "place.step|{\"from\":\"2026-10-13\",\"to\":\"2026-11-01\",\"merged\":1,\"split\":1,\"retired\":1,"
                        + "\"renamed\":1,\"moved\":1,\"added\":3}");
    }

    @Test
    void aRenameOntoAUserPlacesNameMergesItAndALaterMergeCarriesPhotosOnToTheFinalSurvivor() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        int placed = Integer.parseInt(f.one("SELECT COUNT(*) FROM image WHERE place_id IS NOT NULL"));
        UpdatePlaces.Built v2 = UpdatePlaces.fixtureNext();
        UpdatePlaces.Built v3 = UpdatePlaces.derive(v2, "2026-12-01").rename("P12", "TAMHINI").merge("P5", "P6").build();
        PlaceBundles chain = UpdatePlaces.bundles(UpdatePlaces.fixture(), v2, v3);
        f.step(chain, 2);

        PlaceStep.Summary summary = f.step(chain, 3);

        assertThat(f.places()).contains(f.islet + "|P5|gps", f.isletByHand + "|P5|user", f.isletDeleted + "|P5|gps",
                f.inTamhini + "|P12|user");
        assertThat(f.rows("SELECT id, active, replaced_by FROM place WHERE id IN ('P6', 'P7') ORDER BY id"))
                .as("the first merge's place points at the second's, which the views follow")
                .containsExactly("P6|0|P5", "P7|0|P6");
        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id IN ('P5', 'P12') ORDER BY 1, 2"))
                .as("the user place's spelling is kept as another name").containsExactly("P12|Alpha East", "P12|Tamhini",
                        "P5|Beta", "P5|Islet", "P5|Lakeland");
        assertThat(f.rows("SELECT id FROM place WHERE source = 'user'")).containsExactly(f.coast);
        assertThat(summary.mine()).containsExactly(new PlaceStep.Mine(f.tamhini, "Tamhini", "P12", 1, false));
        assertThat(f.rows("SELECT place_id FROM place_recent ORDER BY place_id")).containsExactly("P11", "P5");
        nothingLost(f, placed);
    }

    @Test
    void aMergedStatesUserDistrictsMoveUnderTheSurvivorAndOneNamedLikeADistrictThereMergesIntoIt() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        String beta = f.userPlaces.add("béta", "P3").id();
        long inBeta = f.add("A5", null);
        f.place(beta, inBeta);
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").merge("P2", "P3").build();

        PlaceStep.Summary summary = f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), v2), 2);

        assertThat(f.rows("SELECT id, parent_id, ancestor_path FROM place WHERE id = '" + f.coast + "'"))
                .containsExactly(f.coast + "|P2|/P1/P2/" + f.coast + "/");
        assertThat(f.rows("SELECT id FROM place WHERE id = '" + beta + "'")).isEmpty();
        assertThat(f.places()).contains(inBeta + "|P5|user");
        assertThat(summary.mine()).containsExactly(new PlaceStep.Mine(beta, "béta", "P5", 1, false));
        assertThat(summary.items()).extracting(PlaceStep.Item::type).containsExactly("merge");
        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id = 'P2'")).containsExactly("P2|Leafstate");
    }

    @Test
    void aMergeIntoAPlaceNewInTheSameListTakesItsUserDistrictsAndRecentPlace() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        int placed = Integer.parseInt(f.one("SELECT COUNT(*) FROM image WHERE place_id IS NOT NULL"));
        f.place("P3", f.nowhere);
        f.sql("INSERT OR REPLACE INTO place_recent (place_id, used_at) VALUES ('P2', '2026-10-12T04:00:00Z'), ('P3',"
                + " '2026-10-12T05:00:00Z')");
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01")
                .add("P10", "P1", "state", "Unistate").merge("P10", "P2", "P3").build();

        f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), v2), 2);

        assertThat(f.rows("SELECT id, parent_id, active, replaced_by FROM place WHERE id IN ('P2', 'P3', 'P10', '"
                + f.myAlphaWest + "', '" + f.tamhini + "', '" + f.coast + "') ORDER BY id")).containsExactlyInAnyOrder(
                "P10|P1|1|null", "P2|P1|0|P10", "P3|P1|0|P10", f.tamhini + "|P10|1|null", f.coast + "|P10|1|null",
                f.myAlphaWest + "|P10|1|null");
        assertThat(f.places()).contains(f.nowhere + "|P10|user");
        assertThat(f.rows("SELECT place_id, used_at FROM place_recent WHERE place_id IN ('P2', 'P3', 'P10')"))
                .as("kept once, at the newer time").containsExactly("P10|2026-10-12T05:00:00Z");
        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id = 'P10' ORDER BY name"))
                .containsExactly("P10|Leafstate", "P10|Northstate");
        nothingLost(f, placed + 1);
    }

    @Test
    void namesAStepCarriedSurviveALaterListThatLeavesThemOut() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        UpdatePlaces.Built v2 = UpdatePlaces.fixtureNext();
        UpdatePlaces.Built v3 = UpdatePlaces.derive(v2, "2026-12-01").aliases("P6").aliases("P5", "Bettania").build();
        PlaceBundles chain = UpdatePlaces.bundles(UpdatePlaces.fixture(), v2, v3);
        f.step(chain, 2);
        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id IN ('P5', 'P6') ORDER BY 1, 2"))
                .containsExactly("P5|Beta", "P6|Islet");

        f.step(chain, 3);

        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id IN ('P5', 'P6') ORDER BY 1, 2"))
                .as("the merged and the old names stay; the list's new one is added").containsExactly("P5|Beta",
                        "P5|Bettania", "P6|Islet");
    }

    @Test
    void twoUserPlacesOfOneNameBroughtTogetherByAMergeBecomeOne() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        String coastNorth = f.userPlaces.add("Coast", "P2").id();
        long inCoast = f.add("A5", null);
        f.place(f.coast, inCoast);
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").merge("P2", "P3").build();

        PlaceStep.Summary summary = f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), v2), 2);

        assertThat(summary.mine()).containsExactly(new PlaceStep.Mine(f.coast, "Coast", coastNorth, 1, true));
        assertThat(f.places()).contains(inCoast + "|" + coastNorth + "|user");
        assertThat(f.rows("SELECT id FROM place WHERE id = '" + f.coast + "'")).isEmpty();
    }

    @Test
    void namesCarriedByAnEarlierMergeFollowItsSurvivorAndIncludeTheMergedPlacesOtherNames() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").merge("P5", "P4").build();
        UpdatePlaces.Built v3 = UpdatePlaces.derive(v2, "2026-12-01").aliases("P5").build();
        UpdatePlaces.Built v4 = UpdatePlaces.derive(v3, "2027-01-01").merge("P6", "P5").aliases("P6").build();
        PlaceBundles chain = UpdatePlaces.bundles(UpdatePlaces.fixture(), v2, v3, v4);
        f.step(chain, 2);
        f.step(chain, 3);
        assertThat(f.rows("SELECT name FROM place_alias WHERE place_id = 'P5' ORDER BY name")).containsExactly("Alfa", "Alpha");

        f.step(chain, 4);

        assertThat(f.rows("SELECT name FROM place_alias WHERE place_id = 'P6' ORDER BY name"))
                .as("Alpha's names went to Beta, and with Beta to Lakeland").containsExactly("Alfa", "Alpha", "Beta");
    }

    @Test
    void twoUserPlacesOfOneNameBothMovedUnderANewSurvivorBecomeOne() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        String coastNorth = f.userPlaces.add("Coast", "P2").id();
        long inNorth = f.add("A5", null);
        f.place(coastNorth, inNorth);
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01")
                .add("P10", "P1", "state", "Unistate").merge("P10", "P2", "P3").build();

        PlaceStep.Summary summary = f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), v2), 2);

        assertThat(summary.mine()).containsExactly(new PlaceStep.Mine(coastNorth, "Coast", f.coast, 1, true));
        assertThat(f.rows("SELECT id, parent_id FROM place WHERE source = 'user' AND name = 'Coast'"))
                .containsExactly(f.coast + "|P10");
        assertThat(f.places()).contains(inNorth + "|" + f.coast + "|user");
    }

    @Test
    void aStepThatTouchesNothingOfTheLibraryIsRecordedButNotShown() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").add("P10", null, "country",
                "Newland").rename("P8", "Farland Islands").build();

        PlaceStep.Summary summary = f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), v2), 2);

        assertThat(summary.counts()).isEqualTo(new PlaceStep.Counts(0, 0, 0, 1, 0, 1));
        assertThat(summary.items()).isEmpty();
        assertThat(summary.mine()).isEmpty();
        assertThat(f.rows("SELECT version, touches FROM place_update")).containsExactly("2026-11-01|0");
        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id = 'P8'")).containsExactly("P8|Farland");
    }

    @Test
    void aStepThatFailsLeavesTheLibraryAsItWas() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        List<String> before = f.rows("SELECT * FROM place ORDER BY id");
        List<String> photos = f.places();
        f.sql("CREATE TRIGGER fail_step BEFORE INSERT ON place_update BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.step(PlaceUpdateFixture.next(), 2))
                .hasMessageContaining("Database write failed");

        assertThat(f.rows("SELECT * FROM place ORDER BY id")).isEqualTo(before);
        assertThat(f.places()).isEqualTo(photos);
        assertThat(f.rows("SELECT bundle_version FROM place_state")).containsExactly("2026-10-13");
        assertThat(f.rows("SELECT COUNT(*) FROM change_log WHERE kind LIKE 'bundled.%'")).containsExactly("0");
    }
}
