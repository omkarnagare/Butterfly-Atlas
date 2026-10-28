package com.butterflyatlas.app.window;

import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The log file in the config folder: owner-only, rolling, and never a launch key (audit 1, 10, 11). */
class AtlasLogTest {

    @TempDir
    Path config;

    private final TestClock clock = new TestClock();

    @Test
    void writesDatedLinesToLogsAtlasLogWithTheStackTrace() throws Exception {
        AtlasLog log = AtlasLog.open(config, clock);

        log.info("Butterfly Atlas started on port 7878");
        log.error("It stopped", new IllegalStateException("no library"));

        String text = Files.readString(config.resolve("logs").resolve("atlas.log"));
        assertThat(text).contains("2026-10-11T09:00:00Z INFO Butterfly Atlas started on port 7878")
                .contains("ERROR It stopped").contains("java.lang.IllegalStateException: no library")
                .contains("at com.butterflyatlas.app.window.AtlasLogTest");
        assertThat(log.file()).isEqualTo(config.resolve("logs").resolve("atlas.log"));
    }

    @Test
    void theLogIsReadableByTheOwnerOnly() throws Exception {
        assumeTrue(Files.getFileStore(config).supportsFileAttributeView("posix"));
        AtlasLog log = AtlasLog.open(config, clock);
        log.info("hello");

        assertThat(Files.getPosixFilePermissions(log.file()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(log.file().getParent())).doesNotContainAnyElementsOf(Set.of(
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
    }

    @Test
    void aLaunchKeyNeverReachesTheLog() throws Exception {
        AtlasLog log = AtlasLog.open(config, clock);
        log.hide("sEcReT-value_123");

        log.info("Opening http://127.0.0.1:7878/launch?k=AbC-123_xyz&notice=already-running now");
        log.warn("Cookie was sEcReT-value_123 and again sEcReT-value_123", null);
        log.error("Failed " + new IllegalStateException("bad /launch?k=ZZZ_9"), new RuntimeException("k=QQQ"));

        String text = Files.readString(log.file());
        assertThat(text).doesNotContain("AbC-123_xyz").doesNotContain("sEcReT-value_123").doesNotContain("ZZZ_9")
                .doesNotContain("QQQ");
        assertThat(text).contains("/launch?k=...&notice=already-running");
    }

    @Test
    void rollsToNumberedFilesAndKeepsAFewOfThem() throws Exception {
        AtlasLog log = AtlasLog.open(config, clock, 200, 2);

        for (int i = 0; i < 40; i++) {
            log.info("line number " + i + " " + "x".repeat(20));
        }

        Path logs = config.resolve("logs");
        assertThat(Files.list(logs).map(p -> p.getFileName().toString()).sorted().toList())
                .containsExactly("atlas.1.log", "atlas.2.log", "atlas.log");
        assertThat(Files.size(logs.resolve("atlas.log"))).isLessThan(400);
        assertThat(Files.readString(logs.resolve("atlas.log"))).contains("line number 39");
    }

    @Test
    void aLogThatCannotBeWrittenNeverStopsTheApp() throws Exception {
        Path blocker = Files.writeString(config.resolve("logs"), "a file where the folder should be");

        AtlasLog log = AtlasLog.open(config, clock);
        log.info("still fine");
        log.error("and this", new RuntimeException("x"));

        assertThat(log.file()).isNull();
        assertThat(blocker).exists();
    }

    @Test
    void whatTheAppsOwnLoggerWritesGoesToTheLogOnceStandardErrorIsCaptured() throws Exception {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        PrintStream before = System.err;
        try {
            log.captureStdErr();
            org.slf4j.LoggerFactory.getLogger("com.butterflyatlas.app.SomethingElse").error("the app logged this",
                    new IllegalStateException("with a trace"));
        } finally {
            System.setErr(before);
        }

        String text = Files.readString(log.file(), StandardCharsets.UTF_8);
        assertThat(text).contains("the app logged this").contains("IllegalStateException: with a trace");
    }

    @Test
    void standardErrorCanBeSentToTheLogToo() throws Exception {
        AtlasLog log = AtlasLog.open(config, clock);
        PrintStream before = System.err;
        try {
            log.captureStdErr();
            System.err.println("[main] WARN something from a library k=SECRETKEY1");
            System.err.flush();
        } finally {
            System.setErr(before);
        }

        String text = Files.readString(log.file(), StandardCharsets.UTF_8);
        assertThat(text).contains("something from a library k=...").doesNotContain("SECRETKEY1");
    }
}
