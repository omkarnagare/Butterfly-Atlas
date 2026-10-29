package com.butterflyatlas.app.config;

import com.butterflyatlas.app.OwnerOnly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;

/**
 * Per-user folders, following each operating system's convention (docs/PLAN.md §12). The config folder is the user's alone
 * ({@code rwx------}, {@link #createConfigDir}); in it the pointer file {@code instance.json}, the log folder {@code logs/},
 * the redirect-file folder {@code launch/} and its files are owner-only too (on Windows the user's own profile folder, and
 * each file's access list narrowed to its owner), because the computer may be shared.
 */
public final class AppDirs {

    public static final String APP_NAME = "Butterfly Atlas";

    private AppDirs() {
    }

    /** Where {@code config.json} lives for this user. */
    public static Path configDir(String osName, Map<String, String> env, Path home) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve(APP_NAME);
        }
        if (os.startsWith("windows")) {
            String appData = env.get("APPDATA");
            Path base = appData == null || appData.isBlank() ? home.resolve("AppData").resolve("Roaming") : Path.of(appData);
            return base.resolve(APP_NAME);
        }
        String xdg = env.get("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() || !Path.of(xdg).isAbsolute() ? home.resolve(".config") : Path.of(xdg);
        return base.resolve("butterfly-atlas");
    }

    /** The library folder's name: in Documents on first launch, and inside a folder chosen at setup. */
    public static final String LIBRARY_NAME = "Butterfly_Atlas";

    /**
     * The data folder offered on first launch: a name without a space, as every name the app creates. A library set up
     * before keeps its folder (config.json names it).
     */
    public static Path defaultDataFolder(Path home) {
        return home.resolve("Documents").resolve(LIBRARY_NAME);
    }

    /**
     * Creates the config folder owner-only ({@code rwx------} where the system has POSIX permissions) and makes an older,
     * more open one owner-only too; what is inside is not touched.
     */
    public static void createConfigDir(Path dir) {
        try {
            if (dir.getFileSystem().supportedFileAttributeViews().contains(OwnerOnly.POSIX)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OwnerOnly.FOLDER)));
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(OwnerOnly.FOLDER));
            } else {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + dir, e);
        }
    }

    /** The config folder for this run; {@code -Datlas.configDir} overrides it (tests, development). */
    public static Path currentConfigDir() {
        String override = System.getProperty("atlas.configDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return configDir(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home")));
    }
}
