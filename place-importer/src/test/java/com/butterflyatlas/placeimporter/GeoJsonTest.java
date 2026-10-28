package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoJsonTest {

    @TempDir
    Path dir;

    @Test
    void readsPolygonsAndMultiPolygonsWithTheirPropertiesAsText() throws IOException {
        Path file = write("""
                { "type": "FeatureCollection", "crs": { "type": "name" }, "features": [
                  { "type": "Feature", "properties": { "shapeName": "Pune", "count": 7, "none": null },
                    "geometry": { "type": "Polygon", "coordinates": [
                      [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]],
                      [[2, 2], [2, 4], [4, 4], [4, 2], [2, 2]] ] } },
                  { "type": "Feature", "properties": { "shapeName": "Goa" },
                    "geometry": { "type": "MultiPolygon", "coordinates": [
                      [[[20, 0], [21, 0], [21, 1], [20, 0]]],
                      [[[30, 0], [31, 0], [31, 1], [30, 0]]] ] } } ] }
                """);

        List<GeoJson.Feature> features = GeoJson.read(file);

        assertThat(features).hasSize(2);
        assertThat(features.get(0).properties()).containsEntry("shapeName", "Pune").containsEntry("count", "7")
                .doesNotContainKey("none");
        assertThat(features.get(0).geometry()).isInstanceOf(Polygon.class);
        assertThat(((Polygon) features.get(0).geometry()).getNumInteriorRing()).isEqualTo(1);
        assertThat(features.get(0).geometry().getArea()).isEqualTo(96.0);
        assertThat(features.get(1).geometry()).isInstanceOf(MultiPolygon.class);
        assertThat(features.get(1).geometry().getNumGeometries()).isEqualTo(2);
    }

    @Test
    void roundsCoordinatesToTheBundleGridOfOneHundredThousandthOfADegree() throws IOException {
        Path file = write("""
                { "type": "FeatureCollection", "features": [ { "type": "Feature", "properties": {},
                  "geometry": { "type": "Polygon", "coordinates": [
                    [[73.123456789, 18.000004], [73.2, 18.0], [73.2, 18.1], [73.123456789, 18.000004]] ] } } ] }
                """);

        Geometry geometry = GeoJson.read(file).get(0).geometry();

        assertThat(geometry.getCoordinates()).extracting(c -> c.x + "," + c.y)
                .contains("73.12346,18.0", "73.2,18.1")
                .doesNotContain("73.123456789,18.000004");
        assertThat(geometry.getPrecisionModel().getScale()).isEqualTo(1e5);
    }

    @Test
    void repairsSelfIntersectingRingsSoTheyCanBeOverlaid() throws IOException {
        Path file = write("""
                { "type": "FeatureCollection", "features": [ { "type": "Feature", "properties": {},
                  "geometry": { "type": "Polygon", "coordinates": [ [[0, 0], [2, 2], [2, 0], [0, 2], [0, 0]] ] } } ] }
                """);

        Geometry geometry = GeoJson.read(file).get(0).geometry();

        assertThat(geometry.isValid()).isTrue();
        assertThat(geometry.getArea()).isEqualTo(2.0);
    }

    @Test
    void refusesOtherGeometriesAndFilesWithoutFeatures() throws IOException {
        Path line = write("""
                { "type": "FeatureCollection", "features": [ { "type": "Feature", "properties": { "name": "Road" },
                  "geometry": { "type": "LineString", "coordinates": [[0, 0], [1, 1]] } } ] }
                """);
        assertThatThrownBy(() -> GeoJson.read(line)).isInstanceOf(IOException.class)
                .hasMessageContaining("LineString");

        Path empty = write("{ \"type\": \"FeatureCollection\" }");
        assertThatThrownBy(() -> GeoJson.read(empty)).isInstanceOf(IOException.class)
                .hasMessageContaining("no \"features\"");
    }

    @Test
    void badGeometriesAreRefusedNamingTheFileAndTheFeature() throws IOException {
        String ok = "{ \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"Polygon\","
                + " \"coordinates\": [ [[0, 0], [1, 0], [1, 1], [0, 0]] ] } }";
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"Polygon\", \"coordinates\": [] } }",
                "feature 1: a polygon without rings");
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"MultiPolygon\", \"coordinates\": [ [] ] } }",
                "feature 1: a polygon without rings");
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"Polygon\","
                + " \"coordinates\": [ [[0, 0], [1, 0], [1, 1], [0, 1]] ] } }", "feature 1: a ring is not closed");
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"Polygon\","
                + " \"coordinates\": [ [[0, 0], [1, 0], [0, 0]] ] } }", "feature 1: a ring has 3 points, at least 4 are needed");
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"Polygon\","
                + " \"coordinates\": [ [[0, 0], [1], [1, 1], [0, 0]] ] } }", "feature 1: a point is not [longitude, latitude]");
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": { \"name\": \"Rock\" }, \"geometry\": null }",
                "feature 1: no geometry");
        assertRefused(ok + ", 42", "feature 1 is not an object");
        assertRefused(ok + ", { \"type\": \"Feature\", \"properties\": {}, \"geometry\": { \"type\": \"Polygon\","
                + " \"coordinates\": [ [[0, 0], [0.000001, 0], [0.000001, 0.000001], [0, 0]] ] } }",
                "feature 1: the polygon collapses to nothing on the 1e-5° grid");
    }

    private void assertRefused(String features, String reason) throws IOException {
        Path file = write("{ \"type\": \"FeatureCollection\", \"features\": [ " + features + " ] }");
        assertThatThrownBy(() -> GeoJson.read(file)).isInstanceOf(IOException.class)
                .hasMessage(file + ": " + reason);
    }

    private Path write(String json) throws IOException {
        Path file = Files.createTempFile(dir, "f", ".geojson");
        Files.writeString(file, json);
        return file;
    }
}
