package com.butterflyatlas.placeimporter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.precision.GeometryPrecisionReducer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the polygon features of a GeoJSON FeatureCollection, snapped to the bundle's grid of 1e-5 degree. */
public final class GeoJson {

    /** Every geometry of the tool lives on this grid: the {@code .bin} stores integers in 1e-5 degree. */
    public static final PrecisionModel GRID = new PrecisionModel(1e5);
    public static final GeometryFactory FACTORY = new GeometryFactory(GRID);

    private static final GeometryFactory FLOATING = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Feature(Map<String, String> properties, Geometry geometry) {
    }

    private GeoJson() {
    }

    public static List<Feature> read(Path file) throws IOException {
        List<Feature> features = new ArrayList<>();
        boolean sawFeatures = false;
        try (InputStream in = Files.newInputStream(file); JsonParser parser = new JsonFactory(JSON).createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException(file + " is not a GeoJSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if (!field.equals("features")) {
                    parser.skipChildren();
                    continue;
                }
                sawFeatures = true;
                if (parser.currentToken() != JsonToken.START_ARRAY) {
                    throw new IOException(file + ": \"features\" is not an array");
                }
                for (int index = 0; parser.nextToken() != JsonToken.END_ARRAY; index++) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        throw new IOException(file + ": feature " + index + " is not an object");
                    }
                    JsonNode feature = parser.readValueAsTree();
                    try {
                        features.add(new Feature(properties(feature.path("properties")), geometry(feature.path("geometry"))));
                    } catch (BadGeometry e) {
                        throw new IOException(file + ": feature " + index + ": " + e.getMessage());
                    }
                }
            }
        }
        if (!sawFeatures) {
            throw new IOException(file + " has no \"features\"");
        }
        return features;
    }

    private static Map<String, String> properties(JsonNode node) {
        Map<String, String> properties = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                properties.put(e.getKey(), e.getValue().asText());
            }
        });
        return properties;
    }

    /** A geometry the tool cannot read; {@link #read} adds the file and the feature. */
    private static final class BadGeometry extends Exception {
        BadGeometry(String message) {
            super(message);
        }
    }

    private static Geometry geometry(JsonNode node) throws BadGeometry {
        if (!node.isObject()) {
            throw new BadGeometry("no geometry");
        }
        String type = node.path("type").asText();
        JsonNode coordinates = node.path("coordinates");
        Geometry geometry = switch (type) {
            case "Polygon" -> polygon(coordinates);
            case "MultiPolygon" -> {
                Polygon[] parts = new Polygon[coordinates.size()];
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = polygon(coordinates.get(i));
                }
                yield FLOATING.createMultiPolygon(parts);
            }
            default -> throw new BadGeometry("a " + type + " geometry; only polygons are read");
        };
        if (!geometry.isValid()) {
            geometry = GeometryFixer.fix(geometry);
        }
        Geometry reduced = GeometryPrecisionReducer.reduce(FACTORY.createGeometry(geometry), GRID);
        if (reduced.isEmpty()) {
            throw new BadGeometry("the polygon collapses to nothing on the 1e-5° grid");
        }
        return reduced;
    }

    private static Polygon polygon(JsonNode rings) throws BadGeometry {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new BadGeometry("a polygon without rings");
        }
        LinearRing[] linear = new LinearRing[rings.size()];
        for (int r = 0; r < linear.length; r++) {
            JsonNode ring = rings.get(r);
            Coordinate[] points = new Coordinate[ring.size()];
            for (int p = 0; p < points.length; p++) {
                JsonNode point = ring.get(p);
                if (point.size() < 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    throw new BadGeometry("a point is not [longitude, latitude]");
                }
                points[p] = new Coordinate(point.get(0).asDouble(), point.get(1).asDouble());
            }
            if (points.length < 4) {
                throw new BadGeometry("a ring has " + points.length + " points, at least 4 are needed");
            }
            if (!points[0].equals2D(points[points.length - 1])) {
                throw new BadGeometry("a ring is not closed");
            }
            linear[r] = FLOATING.createLinearRing(points);
        }
        return FLOATING.createPolygon(linear[0], Arrays.copyOfRange(linear, 1, linear.length));
    }
}
