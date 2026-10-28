package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-made place boundaries for tests (degrees): two neighbouring districts sharing an edge, a district with a lake
 * (a hole) holding an island district, a state without districts, and two small countries on either side of the
 * antimeridian.
 *
 * <pre>
 *   P4 Alpha     73.0..74.0 E, 18.0..19.0 N        P5 Beta  74.0..75.0 E, 18.0..19.0 N (shares Alpha's east edge)
 *   P6 Lakeland  76.0..77.0 E, 18.0..19.0 N, with a hole 76.3..76.7 E, 18.3..18.7 N
 *   P7 Islet     76.45..76.55 E, 18.45..18.55 N (inside Lakeland's hole)
 *   P3 Leafstate 80.0..81.0 E, 10.0..11.0 N
 *   P8 Farland   179.0..180.0 E, 17.0..16.0 S       P9 Westfar 180.0..179.0 W, 15.0..14.0 S
 * </pre>
 *
 * The tree: P1 Testland (country, also "Testia") › P2 Northstate › P4 Alpha (also "Alfa"), P5 Beta, P6 Lakeland,
 * P7 Islet; P1 › P3 Leafstate (a state without districts); P8 Farland and P9 Westfar (countries without states).
 */
public final class TestPlaces {

    public static final String VERSION = "2026-10-13";
    public static final String JSON = "places-" + VERSION + ".json";
    public static final String BIN = "places-" + VERSION + ".bin";

    private static volatile PlaceBundles fixture;

    private TestPlaces() {
    }

    /** The place list of the fixture, as the place-importer writes it (contract §2). */
    public static String json() {
        return json(VERSION, BIN);
    }

    public static String json(String version, String geometry) {
        return """
                {
                  "format": 1,
                  "version": "%s",
                  "basedOn": null,
                  "levels": ["country", "state", "district"],
                  "geometry": "%s",
                  "places": [
                    { "id": "P1", "parent": null, "level": "country", "name": "Testland", "aliases": ["Testia"],
                      "codes": { "iso": "TL" } },
                    { "id": "P2", "parent": "P1", "level": "state", "name": "Northstate", "aliases": [],
                      "codes": { "iso": "TL-NS", "lgd": "27" } },
                    { "id": "P3", "parent": "P1", "level": "state", "name": "Leafstate", "aliases": [], "codes": {} },
                    { "id": "P4", "parent": "P2", "level": "district", "name": "Alpha", "aliases": ["Alfa"],
                      "codes": { "lgd": "521" } },
                    { "id": "P5", "parent": "P2", "level": "district", "name": "Beta", "aliases": [], "codes": {} },
                    { "id": "P6", "parent": "P2", "level": "district", "name": "Lakeland", "aliases": [], "codes": {} },
                    { "id": "P7", "parent": "P2", "level": "district", "name": "Islet", "aliases": [], "codes": {} },
                    { "id": "P8", "parent": null, "level": "country", "name": "Farland", "aliases": [], "codes": { "iso": "FL" } },
                    { "id": "P9", "parent": null, "level": "country", "name": "Westfar", "aliases": [], "codes": { "iso": "WF" } }
                  ],
                  "changes": []
                }
                """.formatted(version, geometry);
    }

    /** The fixture's files: the list, the boundaries and a correct {@code places.sha256}. */
    public static Map<String, byte[]> files() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(JSON, json().getBytes(StandardCharsets.UTF_8));
        files.put(BIN, bin());
        files.put(PlaceBundles.CHECKSUMS, checksums(files));
        return files;
    }

    /** {@code places.sha256} for these files: {@code <sha256>  <name>}, sorted by name. */
    public static byte[] checksums(Map<String, byte[]> files) {
        StringBuilder sums = new StringBuilder();
        files.keySet().stream().filter(name -> !name.equals(PlaceBundles.CHECKSUMS)).sorted()
                .forEach(name -> sums.append(TaxonomyBundles.sha256(files.get(name))).append("  ").append(name).append('\n'));
        return sums.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The fixture's bundles (made once; they are immutable). */
    public static PlaceBundles bundles() {
        PlaceBundles made = fixture;
        if (made == null) {
            made = PlaceBundles.load(files());
            fixture = made;
        }
        return made;
    }

    /** Writes the files into {@code dir} and returns it. */
    public static Path write(Path dir, Map<String, byte[]> files) {
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Files.write(dir.resolve(file.getKey()), file.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    public static List<PlaceBin.Area> areas() {
        return List.of(
                PlaceBin.box("P4", 73.0, 18.0, 74.0, 19.0),
                PlaceBin.box("P5", 74.0, 18.0, 75.0, 19.0),
                new PlaceBin.Area("P6", List.of(PlaceBin.rectangle(76.0, 18.0, 77.0, 19.0),
                        PlaceBin.rectangle(76.3, 18.3, 76.7, 18.7))),
                PlaceBin.box("P7", 76.45, 18.45, 76.55, 18.55),
                PlaceBin.box("P3", 80.0, 10.0, 81.0, 11.0),
                PlaceBin.box("P8", 179.0, -17.0, 180.0, -16.0),
                PlaceBin.box("P9", -180.0, -15.0, -179.0, -14.0));
    }

    /** The gzipped boundary file of {@link #areas()}. */
    public static byte[] bin() {
        return PlaceBin.encode(areas());
    }
}
