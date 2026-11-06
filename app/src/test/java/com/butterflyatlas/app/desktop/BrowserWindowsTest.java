package com.butterflyatlas.app.desktop;

import com.butterflyatlas.app.testing.FakeProcess;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which browser process shows our windows (spec §3.2, contract §4): the first one still running after the early-exit
 * time; not one that handed its window over and exited; the next one when it exits; and, with none known, the one the
 * profile names, if it is the browser program.
 */
class BrowserWindowsTest {

    private static final long EARLY = WindowLauncher.EARLY_NANOS;
    private static final Path CHROME = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");

    @TempDir
    Path config;

    private final AtomicLong nanos = new AtomicLong(1_000L);
    private final Map<Long, Path> running = new HashMap<>();
    private final List<Path> asked = new ArrayList<>();
    private OptionalLong holder = OptionalLong.empty();
    private RuntimeException holderFails;
    private Optional<Path> browser = Optional.of(CHROME);
    private AtlasLog log;
    private BrowserWindows windows;

    @BeforeEach
    void setUp() {
        log = AtlasLog.open(config, new TestClock());
        windows = new BrowserWindows(() -> browser, config.resolve("browser_profile"), (program, profile) -> {
            asked.add(profile);
            if (holderFails != null) {
                throw holderFails;
            }
            return holder;
        }, pid -> Optional.ofNullable(running.get(pid)), nanos::get, log);
    }

    private FakeProcess start(long pid) {
        FakeProcess process = new FakeProcess(pid);
        windows.started(process, nanos.get());
        return process;
    }

    @Test
    void theFirstProcessStillRunningAfterTheEarlyExitTimeIsTheBrowserProcess() {
        start(11);
        nanos.addAndGet(EARLY + 1);

        assertThat(windows.pid()).hasValue(11);
    }

    @Test
    void aProcessThatHandsItsWindowOverAndExitsIsNeverTheBrowserProcess() {
        start(11);
        nanos.addAndGet(EARLY + 1);
        assertThat(windows.pid()).hasValue(11);

        FakeProcess handOff = start(12);
        nanos.addAndGet(500_000_000L);
        handOff.exits(0);
        nanos.addAndGet(EARLY);

        assertThat(windows.pid()).hasValue(11);
    }

    @Test
    void whileTheFirstIsStillYoungNoneIsKeptAndTheProfileIsAsked() {
        start(11);
        holder = OptionalLong.of(11);
        running.put(11L, CHROME);

        assertThat(windows.pid()).as("the profile names the young process, and it is the browser program").hasValue(11);
        assertThat(asked).containsExactly(config.resolve("browser_profile"));
    }

    @Test
    void whenTheBrowserProcessExitsTheNextOneThatKeepsRunningTakesItsPlace() {
        FakeProcess first = start(11);
        nanos.addAndGet(EARLY + 1);
        assertThat(windows.pid()).hasValue(11);

        first.exits(0);
        start(12);
        nanos.addAndGet(EARLY + 1);

        assertThat(windows.pid()).hasValue(12);
    }

    @Test
    void withNoneStartedTheProcessIsFoundFromTheProfileAndKeptWhileItRuns() {
        holder = OptionalLong.of(99);
        running.put(99L, CHROME);

        assertThat(windows.pid()).hasValue(99);
        assertThat(windows.pid()).hasValue(99);
        assertThat(asked).as("kept, not looked up again while it runs").hasSize(1);

        running.remove(99L);
        holder = OptionalLong.empty();
        assertThat(windows.pid()).isEmpty();
        assertThat(asked).hasSize(2);
    }

    @Test
    void aStartThatHandsOffAtOnceFindsTheBrowserAlreadyRunningOnTheProfile() {
        FakeProcess handOff = start(12);
        handOff.exits(0);
        holder = OptionalLong.of(99);
        running.put(99L, CHROME);

        assertThat(windows.pid()).hasValue(99);
    }

    @Test
    void aProcessTheProfileNamesIsIgnoredWhenItIsNotTheBrowserProgram() {
        holder = OptionalLong.of(99);
        running.put(99L, Path.of("/usr/bin/vim"));

        assertThat(windows.pid()).isEmpty();
    }

    @Test
    void aProcessTheProfileNamesIsIgnoredWhenItIsNoLongerRunning() {
        holder = OptionalLong.of(99);

        assertThat(windows.pid()).isEmpty();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "symbolic links")
    void theBrowserProgramMayBeALinkToALauncherInTheProcesssFolder() throws Exception {
        // Linux: /usr/bin/google-chrome leads to the script /opt/google/chrome/google-chrome, which starts .../chrome
        Path opt = Files.createDirectories(config.resolve("opt/google/chrome"));
        Files.writeString(opt.resolve("google-chrome"), "#!/bin/sh");
        Files.writeString(opt.resolve("chrome"), "");
        Path bin = Files.createDirectories(config.resolve("bin"));
        Files.createSymbolicLink(bin.resolve("google-chrome"), opt.resolve("google-chrome"));
        browser = Optional.of(bin.resolve("google-chrome"));
        holder = OptionalLong.of(99);
        running.put(99L, opt.resolve("chrome"));

        assertThat(windows.pid()).hasValue(99);
    }

    @Test
    void aProgramMerelyNextToTheBrowserIsNotTheBrowser() {
        browser = Optional.of(Path.of("/usr/bin/chromium"));
        holder = OptionalLong.of(99);
        running.put(99L, Path.of("/usr/bin/vim"));

        assertThat(windows.pid()).isEmpty();
    }

    @Test
    void withNoBrowserProgramNothingIsLookedUp() {
        browser = Optional.empty();
        holder = OptionalLong.of(99);
        running.put(99L, CHROME);

        assertThat(windows.pid()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void aLookupThatFailsIsLoggedOnceAndFindsNothing() throws Exception {
        holderFails = new IllegalStateException("no access");

        assertThat(windows.pid()).isEmpty();
        assertThat(windows.pid()).isEmpty();

        String text = Files.readString(log.file());
        assertThat(text.split("could not find the browser", -1)).hasSize(2);
    }

    @Test
    void aProcessWithNoIdIsNeverKept() {
        windows.started(new FakeProcess(), nanos.get());
        nanos.addAndGet(EARLY + 1);

        assertThat(windows.pid()).isEmpty();
    }
}
