package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every package except the web layer, the desktop imaging adapter and the OS shell for the app's icon must not depend
 * on Javalin, AWT/Swing or the web layer, so the same logic can later run behind a different shell (design D6:
 * e.g. an Android app). Files directly in the root package (Main, AtlasServer) are the shell and are
 * excluded. {@code desktop} is shell like {@code imaging}: it is the app's icon in the Dock, the taskbar or the
 * launcher, and on macOS it hears the Dock through {@code java.awt.desktop} and {@code java.awt.Taskbar}.
 */
class CorePackagesTest {

    private static final Path ROOT = Path.of("src/main/java/com/butterflyatlas/app");
    private static final List<String> SHELL = List.of("web", "imaging", "desktop");
    private static final List<String> FORBIDDEN = List.of("io.javalin.", "java.awt.", "javax.swing.",
            "com.butterflyatlas.app.web.");

    static List<String> corePackages() throws IOException {
        try (Stream<Path> dirs = Files.list(ROOT)) {
            return dirs.filter(Files::isDirectory).map(d -> d.getFileName().toString())
                    .filter(name -> !SHELL.contains(name)).sorted().toList();
        }
    }

    @Test
    void theExpectedCorePackagesAreChecked() throws Exception {
        assertThat(corePackages()).contains("config", "db", "taxonomy", "library", "taxa", "search", "photos", "imports", "places",
                        "filters", "exports", "custom", "updates")
                .doesNotContain("web", "imaging", "desktop");
    }

    @ParameterizedTest
    @MethodSource("corePackages")
    void coreStaysPortable(String pkg) throws Exception {
        Path dir = ROOT.resolve(pkg);
        assertThat(dir).isDirectory();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(dir)) {
            sources = files.filter(f -> f.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            String code = Files.readString(source);
            for (String forbidden : FORBIDDEN) {
                assertThat(code).as(source + " must not use " + forbidden).doesNotContain(forbidden);
            }
        }
    }
}
