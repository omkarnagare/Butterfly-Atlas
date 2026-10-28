package com.butterflyatlas.app.window;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Finds the browser the app window opens in (design §2). Windows: Microsoft Edge, then Chrome; macOS: Chrome, then Edge;
 * Linux: Chrome, Chromium, then Edge. {@code -Datlas.browser=<program>} or env {@code ATLAS_BROWSER} names one
 * explicitly (developers and tests); one that is not there is said ({@link Found#note}) and the usual search goes on.
 */
public final class BrowserFinder {

    /** The browser found, if any, and a sentence for the log when the explicit choice could not be used. */
    public record Found(Optional<Path> browser, String note) {
    }

    private static final List<String> LINUX = List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
            "microsoft-edge", "microsoft-edge-stable");

    private final String os;
    private final Map<String, String> env;
    private final Path home;
    private final String explicit;
    private final Predicate<Path> runnable;
    /** The folders macOS keeps applications in, besides the user's own {@code ~/Applications}. */
    private final List<Path> applications;

    /** With the system's name, environment and home, the explicit choice (null: none) and what tells a program is there. */
    public BrowserFinder(String osName, Map<String, String> env, Path home, String explicit, Predicate<Path> runnable) {
        this(osName, env, home, explicit, runnable, List.of(Path.of("/Applications")));
    }

    BrowserFinder(String osName, Map<String, String> env, Path home, String explicit, Predicate<Path> runnable,
                  List<Path> applications) {
        this.applications = applications;
        this.os = osName.toLowerCase(Locale.ROOT);
        this.env = os.startsWith("windows") ? ignoringCase(env) : env;
        this.home = home;
        this.explicit = explicit;
        this.runnable = runnable;
    }

    /** This system: the property, then the environment variable, name a browser explicitly. */
    public static BrowserFinder system() {
        return new BrowserFinder(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home")),
                explicit(() -> System.getProperty("atlas.browser"), System.getenv()::get), java.nio.file.Files::isExecutable);
    }

    /** {@code -Datlas.browser}, else {@code ATLAS_BROWSER}; null when neither is set. */
    static String explicit(Supplier<String> property, Function<String, String> env) {
        String p = property.get();
        if (p != null && !p.isBlank()) {
            return p.strip();
        }
        String e = env.apply("ATLAS_BROWSER");
        return e == null || e.isBlank() ? null : e.strip();
    }

    public Found find() {
        String note = null;
        if (explicit != null) {
            Path chosen = path(explicit);
            if (chosen != null && runnable.test(chosen)) {
                return new Found(Optional.of(chosen), null);
            }
            note = "The browser named in atlas.browser / ATLAS_BROWSER (" + explicit + ") is not a program that can be"
                    + " started; looking in the usual places instead.";
        }
        for (Path candidate : candidates()) {
            if (runnable.test(candidate)) {
                return new Found(Optional.of(candidate), note);
            }
        }
        return new Found(Optional.empty(), note);
    }

    private List<Path> candidates() {
        List<Path> found = new ArrayList<>();
        if (os.startsWith("windows")) {
            List<Path> bases = new ArrayList<>();
            for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                Path base = path(env.get(key));
                if (base != null) {
                    bases.add(base);
                }
            }
            for (String relative : List.of("Microsoft/Edge/Application/msedge.exe", "Google/Chrome/Application/chrome.exe")) {
                for (Path base : bases) {
                    found.add(base.resolve(relative));
                }
            }
        } else if (os.startsWith("mac")) {
            List<Path> roots = new ArrayList<>(applications);
            roots.add(home.resolve("Applications"));
            for (String relative : List.of("Google Chrome.app/Contents/MacOS/Google Chrome",
                    "Microsoft Edge.app/Contents/MacOS/Microsoft Edge")) {
                for (Path root : roots) {
                    found.add(root.resolve(relative));
                }
            }
        } else {
            String path = env.get("PATH");
            for (String name : LINUX) {
                if (path == null) {
                    break;
                }
                for (String dir : path.split(File.pathSeparator)) {
                    Path folder = path(dir);
                    if (folder != null) {
                        found.add(folder.resolve(name));
                    }
                }
            }
        }
        return found;
    }

    /**
     * Windows names its variables regardless of case, but {@link System#getenv()}'s map matches them exactly, and a
     * launcher may change their case (Maven's Surefire upper-cases every name, so "ProgramFiles(x86)" was not found).
     */
    private static Map<String, String> ignoringCase(Map<String, String> env) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(env);
        return map;
    }

    private static Path path(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
