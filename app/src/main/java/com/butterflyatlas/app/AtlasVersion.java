package com.butterflyatlas.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The application version, written into {@code atlas-version.properties} by the Maven build. */
public final class AtlasVersion {

    private static final String UNKNOWN = "dev";

    private AtlasVersion() {
    }

    public static String current() {
        try (InputStream in = AtlasVersion.class.getResourceAsStream("/atlas-version.properties")) {
            if (in == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", UNKNOWN);
            // An unfiltered placeholder means the file was copied without Maven filtering (e.g. an IDE build).
            return version.startsWith("${") ? UNKNOWN : version;
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
