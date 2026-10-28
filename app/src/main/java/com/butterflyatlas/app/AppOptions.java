package com.butterflyatlas.app;

import com.butterflyatlas.app.config.AppDirs;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the app was started with (the system properties and environment {@link Main} documents), read once and checked.
 * {@code port} is null when none was named: the fixed port is tried first, then any free one (design §15, D1).
 */
record AppOptions(boolean noBrowser, Integer port, Optional<Path> dataDir, Path configDir, int quitSeconds,
                  int startSeconds, Optional<Path> taxonomyDir, Optional<Path> placesDir) {

    static final int QUIT_SECONDS = 10;
    static final int START_SECONDS = 60;

    /** This run's settings; a setting that cannot be used is an {@link IllegalArgumentException} saying which and why. */
    static AppOptions fromSystem() {
        return of(System::getProperty, System::getenv, AppDirs.currentConfigDir());
    }

    static AppOptions of(Function<String, String> property, Function<String, String> env, Path configDir) {
        String port = property.apply("atlas.port");
        Integer fixed = null;
        if (port != null && !port.isBlank()) {
            fixed = Main.parsePort("atlas.port", port);
        } else if (env.apply("ATLAS_PORT") != null && !env.apply("ATLAS_PORT").isBlank()) {
            fixed = Main.parsePort("ATLAS_PORT", env.apply("ATLAS_PORT"));
        }
        return new AppOptions(Boolean.parseBoolean(property.apply("atlas.noBrowser")), fixed,
                path(property.apply("atlas.dataDir")), configDir,
                seconds("atlas.quitSeconds", property.apply("atlas.quitSeconds"), QUIT_SECONDS),
                seconds("atlas.startSeconds", property.apply("atlas.startSeconds"), START_SECONDS),
                path(property.apply("atlas.taxonomyDir")), path(property.apply("atlas.placesDir")));
    }

    private static Optional<Path> path(String value) {
        return Optional.ofNullable(value).filter(s -> !s.isBlank()).map(Path::of);
    }

    private static int seconds(String name, String value, int otherwise) {
        if (value == null || value.isBlank()) {
            return otherwise;
        }
        try {
            int seconds = Integer.parseInt(value.trim());
            if (seconds >= 1) {
                return seconds;
            }
        } catch (NumberFormatException e) {
            // fall through to the shared message below
        }
        throw new IllegalArgumentException(name + " must be a whole number of seconds, 1 or more, got: " + value);
    }
}
