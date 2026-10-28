package com.butterflyatlas.placeimporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the bundle committed under app/src/main/resources/places with the independent {@link BinDecoder} and plain
 * Jackson (none of the tool's own classes) and checks it against contract §2 and the §5 test points; and the chain of
 * lists (5c contract §3): the 5c list differs from the first by exactly its one merge.
 */
class CommittedBundleTest {

    private static final Path PLACES = Path.of("../app/src/main/resources/places");
    private static JsonNode json;
    private static List<String> jsons;
    private static Map<String, JsonNode> byId;
    private static List<BinDecoder.Entry> entries;
    private static String version;
    private static long binSize;

    @BeforeAll
    static void read() throws IOException {
        try (Stream<Path> files = Files.list(PLACES)) {
            jsons = files.map(f -> f.getFileName().toString()).filter(n -> n.matches("places-\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .sorted().toList();
        }
        assertThat(jsons).isNotEmpty();
        String newest = jsons.get(jsons.size() - 1);
        version = newest.substring("places-".length(), newest.length() - ".json".length());
        json = new ObjectMapper().readTree(Files.readString(PLACES.resolve(newest)));
        byId = new HashMap<>();
        json.path("places").forEach(p -> byId.put(p.path("id").asText(), p));
        byte[] bin = Files.readAllBytes(PLACES.resolve(json.path("geometry").asText()));
        binSize = bin.length;
        entries = BinDecoder.decode(bin);
    }

    @Test
    void theChecksumListNamesEveryBundleFileWithItsSha256() throws Exception {
        Map<String, String> listed = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(PLACES.resolve("places.sha256"))) {
            String[] parts = line.split("  ", 2);
            assertThat(parts[0]).matches("[0-9a-f]{64}");
            listed.put(parts[1], parts[0]);
            names.add(parts[1]);
        }
        assertThat(names).isSorted();
        try (Stream<Path> files = Files.list(PLACES)) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("places-")).sorted())
                    .containsExactlyElementsOf(names);
        }
        for (String name : names) {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(PLACES.resolve(name))));
            assertThat(listed.get(name)).as(name).isEqualTo(sha);
        }
        assertThat(PLACES.resolve("NOTICE.txt")).exists();
    }

    @Test
    void theJsonFollowsTheContract() {
        assertThat(json.path("format").asInt()).isEqualTo(1);
        assertThat(json.path("version").asText()).isEqualTo(version);
        assertThat(json.path("basedOn").asText()).isEqualTo(jsons.get(jsons.size() - 2).substring("places-".length(), 17));
        assertThat(json.path("levels").toString()).isEqualTo("[\"country\",\"state\",\"district\"]");
        assertThat(json.path("geometry").asText()).isEqualTo("places-" + version + ".bin");
        assertThat(json.path("changes").isArray()).isTrue();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : json.path("places")) {
            String id = p.path("id").asText();
            assertThat(id).matches("P[1-9]\\d*");
            assertThat(seen.add(id)).as("unique %s", id).isTrue();
            String level = p.path("level").asText();
            JsonNode parent = p.path("parent");
            switch (level) {
                case "country" -> assertThat(parent.isNull()).as(id).isTrue();
                case "state" -> assertThat(byId.get(parent.asText()).path("level").asText()).as(id).isEqualTo("country");
                case "district" -> assertThat(byId.get(parent.asText()).path("level").asText()).as(id).isEqualTo("state");
                default -> throw new AssertionError("level " + level);
            }
            if (!parent.isNull()) {
                assertThat(seen).as("parent of %s listed first", id).contains(parent.asText());
            }
            assertThat(p.path("name").asText()).isNotBlank();
            assertThat(p.path("aliases").isArray()).isTrue();
            p.path("aliases").forEach(a -> assertThat(a.asText()).isNotEqualTo(p.path("name").asText()));
            p.path("codes").fieldNames().forEachRemaining(k -> assertThat(k).isIn("iso", "lgd", "gb", "ne"));
        }
    }

    @Test
    void theListsAreOneChainAndThe5cListDiffersFromTheFirstByExactlyTheBrezniceMerge() throws IOException {
        assertThat(jsons).containsExactly("places-2026-10-13.json", "places-2026-10-16.json");
        JsonNode first = new ObjectMapper().readTree(Files.readString(PLACES.resolve(jsons.get(0))));
        assertThat(first.path("basedOn").isNull()).isTrue();
        assertThat(first.path("changes")).isEmpty();
        assertThat(json.path("version").asText()).isEqualTo("2026-10-16");
        assertThat(json.path("basedOn").asText()).isEqualTo("2026-10-13");
        assertThat(json.path("changes").toString()).isEqualTo("[{\"type\":\"merge\",\"from\":[\"P4245\"],\"to\":[\"P4187\"],"
                + "\"detail\":\"Brežice (Pečice) merged into Brežice\"}]");
        try (Stream<Path> files = Files.list(PLACES)) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".bin")))
                    .as("only the newest list's boundaries ship").containsExactly("places-2026-10-16.bin");
        }

        Map<String, JsonNode> before = new HashMap<>();
        first.path("places").forEach(p -> before.put(p.path("id").asText(), p));
        Map<String, JsonNode> after = new HashMap<>(byId);
        assertThat(after).doesNotContainKey("P4245");
        assertThat(before.get("P4245").path("name").asText()).isEqualTo("Brežice (Pečice)");
        assertThat(after.get("P4187").path("name").asText()).isEqualTo("Brežice");
        assertThat(after.get("P4187").path("aliases").toString()).isEqualTo("[\"Brežice (Pečice)\",\"Krsko\"]");
        assertThat(before.get("P4187").path("aliases")).isEmpty();
        assertThat(after.get("P161").path("name").asText()).as("Shahdara keeps its id").isEqualTo("Shahdara");
        before.remove("P4245");
        before.remove("P4187");
        after.remove("P4187");
        assertThat(after).as("nothing else differs").isEqualTo(before);
        assertThat(path(46.005, 15.575)).as("Pečice").containsExactly("Brežice", "Slovenia");
    }

    @Test
    void siblingsNeverShareANameEvenIgnoringAccentsAndNoAliasIsGarbled() {
        Map<String, String> seen = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        List<String> garbled = new ArrayList<>();
        for (JsonNode p : json.path("places")) {
            String key = p.path("parent").asText("") + "/" + Names.fold(p.path("name").asText());   // as search compares
            String other = seen.putIfAbsent(key, p.path("id").asText());
            if (other != null) {
                clashes.add(other + " " + p.path("id").asText() + " " + p.path("name").asText());
            }
            for (JsonNode alias : p.path("aliases")) {
                String a = alias.asText();
                if (a.codePoints().filter(Character::isLetter).count() < 2 || a.indexOf('\ufffd') >= 0
                        || a.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL
                        || Character.getType(c) == Character.CURRENCY_SYMBOL)) {
                    garbled.add(p.path("id").asText() + " " + a);
                }
            }
        }
        assertThat(clashes).isEmpty();
        assertThat(garbled).isEmpty();
        assertThat(byId.get("P5371").path("name").asText()).isEqualTo("Washington");
        assertThat(byId.get("P5372").path("name").asText()).isEqualTo("District of Columbia");
        assertThat(byId.get("P5372").path("aliases").toString()).contains("\"Washington\"");
        assertThat(byId.get("P3916").path("name").asText()).isEqualTo("Moscow");
        assertThat(byId.get("P3915").path("name").asText()).isEqualTo("Moscow Oblast");
        assertThat(byId.get("P3916").path("aliases").toString()).doesNotContain("Mosc\u00a3");
        assertThat(byId.get("P406").path("aliases").toString()).as("Mumbai").contains("\"Bombay\"");
        Map<String, String> names = Map.of("P5058", "Kyiv", "P5057", "Kyiv Oblast", "P1577", "City of Zagreb",
                "P1576", "Zagreb County", "P1288", "Sofia City", "P1289", "Sofia Province", "P2709", "Rēzekne",
                "P2708", "Rēzekne Municipality");
        names.forEach((id, name) -> assertThat(byId.get(id).path("name").asText()).as(id).isEqualTo(name));
        assertThat(byId.get("P5057").path("aliases").toString()).as("Kiev still finds Kyiv").contains("\"Kiev\"");
        assertThat(byId.get("P5058").path("aliases").toString()).contains("\"Kiev City\"");
        assertThat(byId.get("P414").path("aliases").toString()).as("Pune").contains("\"Poona\"");
        assertThat(byId.get("P4187").path("aliases").toString()).as("SVN-939, merged into Brežice")
                .contains("\"Brežice (Pečice)\"");
        assertThat(byId.get("P4246").path("name").asText()).isEqualTo("Krško");
        Map<String, String> former = Map.of("P407", "Bombay Suburban", "P321", "Quilon", "P316", "Alleppey",
                "P325", "Palghat", "P599", "Tanjore", "P604", "Tuticorin", "P19", "Cuddapah", "P411", "Nasik",
                "P65", "Gauhati", "P731", "Benares");
        former.forEach((id, alias) -> assertThat(byId.get(id).path("aliases").toString()).as(id).contains("\"" + alias + "\""));
        assertThat(byId.get("P731").path("aliases").toString()).contains("\"Banaras\"");
    }

    @Test
    void everyBoundaryIsAPlaceWithClosedRingsInsideItsBoxAndNoneCrossesTheAntimeridian() {
        Set<String> ids = new HashSet<>();
        Map<String, Set<String>> childrenOf = new HashMap<>();
        json.path("places").forEach(p -> {
            if (!p.path("parent").isNull()) {
                childrenOf.computeIfAbsent(p.path("parent").asText(), k -> new HashSet<>()).add(p.path("id").asText());
            }
        });
        for (BinDecoder.Entry e : entries) {
            assertThat(byId).as("boundary %s is a place", e.id()).containsKey(e.id());
            assertThat(ids.add(e.id())).as("one boundary per place: %s", e.id()).isTrue();
            String level = byId.get(e.id()).path("level").asText();
            String country = level.equals("country") ? e.id()
                    : level.equals("state") ? byId.get(e.id()).path("parent").asText()
                    : byId.get(byId.get(e.id()).path("parent").asText()).path("parent").asText();
            if (!childrenOf.getOrDefault(e.id(), Set.of()).isEmpty()) {
                // A place with children has a boundary only for an area none of its children covers: an Indian
                // state's area without a district (deviation 1 in the plan) or a country's area without a state.
                assertThat(level).as(e.id()).isIn("state", "country");
            }
            assertThat(country).isNotBlank();
            int[] b = e.bbox();
            for (int[] ring : e.rings()) {
                for (int i = 0; i < ring.length; i += 2) {
                    assertThat(ring[i]).isBetween(b[0], b[2]);
                    assertThat(ring[i + 1]).isBetween(b[1], b[3]);
                    if (i + 3 < ring.length) {
                        assertThat(Math.abs((long) ring[i + 2] - ring[i])).as(e.id()).isLessThanOrEqualTo(18_000_000L);
                    }
                }
            }
        }
        assertThat(binSize).isLessThanOrEqualTo(4L * 1024 * 1024);
    }

    @Test
    void theContractTestPointsLandWhereTheyShould() {
        assertThat(path(34.1526, 77.5771)).contains("Ladakh").endsWith("India");
        assertThat(path(27.5860, 91.8594)).contains("Arunachal Pradesh").endsWith("India");
        assertThat(path(18.5204, 73.8567)).startsWith("Pune", "Maharashtra", "India");
        assertThat(path(34.3700, 73.4711)).endsWith("India");
        assertThat(path(27.7172, 85.3240)).endsWith("Nepal");
        assertThat(path(15.30, 73.895).get(0)).isIn("North Goa", "South Goa");
        assertThat(path(15.0, 65.0)).isEmpty();
    }

    @Test
    void moreLookupsAcrossIndiaAndTheWorld() {
        assertThat(path(28.6139, 77.2090)).endsWith("Delhi", "India");                    // New Delhi
        assertThat(path(19.0760, 72.8777)).contains("Maharashtra");                         // Mumbai
        assertThat(path(12.9716, 77.5946)).contains("Karnataka");                           // Bengaluru
        assertThat(path(35.0, 79.0)).contains("Ladakh");                                    // Aksai Chin, India's view
        assertThat(path(35.9, 74.3)).endsWith("India");                                     // Gilgit, India's view
        assertThat(path(31.5204, 74.3587)).endsWith("Pakistan");                            // Lahore
        assertThat(path(51.5074, -0.1278)).endsWith("United Kingdom");                      // London
        assertThat(path(-33.8688, 151.2093)).endsWith("Australia");                         // Sydney
        assertThat(path(10.0, -30.0)).isEmpty();                                            // mid-Atlantic
    }

    private static List<String> path(double lat, double lon) {
        List<String> names = new ArrayList<>();
        for (String id = BinDecoder.lookup(entries, lat, lon); id != null; ) {
            JsonNode place = byId.get(id);
            names.add(place.path("name").asText());
            id = place.path("parent").isNull() ? null : place.path("parent").asText();
        }
        return names;
    }
}
