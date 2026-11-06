package com.butterflyatlas.app.desktop.windows;

import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser's windows get our taskbar properties (spec §4.2, contract §4): looked for every 100 ms for up to 5 s after
 * a window is coming, set at once and 2 s later when one connects; only for the installed app in window mode; a failure
 * is logged once and nothing throws.
 */
class WindowsDesktopTest {

    private static final String APP = "C:\\Users\\a\\AppData\\Local\\Programs\\Butterfly_Atlas\\Butterfly Atlas.exe";

    @TempDir
    Path config;

    /** The scheduled tasks, run by the test: each with its delay. */
    private record Task(long delayMillis, Runnable task) {
    }

    private final Deque<Task> scheduled = new ArrayDeque<>();
    private final List<Long> adoptedPids = new ArrayList<>();
    private final List<Path> adoptedPaths = new ArrayList<>();
    private final Deque<Integer> answers = new ArrayDeque<>();
    private Throwable fails;
    private OptionalLong pid = OptionalLong.of(4321);
    private final AtomicReference<WindowLauncher.Mode> mode = new AtomicReference<>(WindowLauncher.Mode.WINDOW);
    private String appPath = APP;
    private AtlasLog log;
    private WindowsDesktop desktop;

    @BeforeEach
    void setUp() {
        log = AtlasLog.open(config, new TestClock());
        desktop = new WindowsDesktop((processId, path) -> {
            adoptedPids.add(processId);
            adoptedPaths.add(path);
            if (fails instanceof RuntimeException e) {
                throw e;
            }
            if (fails instanceof Error e) {
                throw e;
            }
            return answers.isEmpty() ? 0 : answers.poll();
        }, () -> pid, mode::get, () -> appPath, (task, delayMillis) -> scheduled.add(new Task(delayMillis, task)), log);
    }

    /** Runs what is scheduled, in order, until nothing is left; the delays seen. */
    private List<Long> runAll() {
        List<Long> delays = new ArrayList<>();
        while (!scheduled.isEmpty()) {
            Task next = scheduled.poll();
            delays.add(next.delayMillis());
            next.task().run();
        }
        return delays;
    }

    private String logText() throws Exception {
        return Files.readString(log.file());
    }

    @Test
    void theAppUserModelIdIsTheOneTheShortcutsCarry() {
        assertThat(WindowsDesktop.APP_ID).isEqualTo("WeAreNaturalists.ButterflyAtlas");
    }

    @Test
    void afterAWindowIsComingItIsLookedForEvery100MsUntilOneIsSet() {
        answers.addAll(List.of(0, 0, 1));

        desktop.windowComing();
        List<Long> delays = runAll();

        assertThat(delays).containsExactly(100L, 100L, 100L);
        assertThat(adoptedPids).containsExactly(4321L, 4321L, 4321L);
        assertThat(adoptedPaths).containsOnly(Path.of(APP));
    }

    @Test
    void theLookingStopsAfterFiveSeconds() {
        desktop.windowComing();
        List<Long> delays = runAll();

        assertThat(delays).hasSize(50).containsOnly(100L);
        assertThat(delays.stream().mapToLong(Long::longValue).sum()).isEqualTo(5000L);
        assertThat(adoptedPids).hasSize(50);
    }

    @Test
    void aConnectedWindowIsSetAtOnceAndAgainTwoSecondsLater() {
        answers.addAll(List.of(1, 1));

        desktop.windowConnected();
        List<Long> delays = runAll();

        assertThat(delays).containsExactly(0L, 2000L);
        assertThat(adoptedPids).hasSize(2);
    }

    @Test
    void nothingIsSetInADevelopmentRunWithoutTheInstalledProgram() {
        appPath = null;
        desktop.windowComing();
        desktop.windowConnected();
        appPath = " ";
        desktop.windowComing();
        runAll();

        assertThat(adoptedPids).isEmpty();
    }

    @Test
    void nothingIsSetForTheDefaultBrowsersTabs() {
        mode.set(WindowLauncher.Mode.TAB);

        desktop.windowComing();
        desktop.windowConnected();
        runAll();

        assertThat(adoptedPids).isEmpty();
    }

    @Test
    void withNoBrowserProcessKnownNothingIsSetAndThatIsLoggedOnce() throws Exception {
        pid = OptionalLong.empty();

        desktop.windowConnected();
        runAll();

        assertThat(adoptedPids).isEmpty();
        assertThat(logText().split("no browser process", -1)).hasSize(2);
    }

    @Test
    void jnaThatWillNotLoadIsLoggedOnceAndThenNothingIsTriedAgain() throws Exception {
        fails = new UnsatisfiedLinkError("jnidispatch");

        desktop.windowComing();
        runAll();
        desktop.windowConnected();
        runAll();

        assertThat(adoptedPids).hasSize(1);
        assertThat(logText().split("could not load", -1)).hasSize(2);
    }

    @Test
    void aNativeFailureIsLoggedOnceAndTheNextConnectTriesAgain() throws Exception {
        fails = new IllegalStateException("SHGetPropertyStoreForWindow failed: 0x80070005");

        desktop.windowConnected();
        runAll();

        assertThat(adoptedPids).hasSize(2);
        assertThat(logText().split("could not give the window", -1)).hasSize(2);
    }

    @Test
    void theRestDoesNothingOnWindows() {
        assertThat(desktop.browserArguments()).isEmpty();
        assertThat(desktop.bringForward()).isFalse();
        assertThat(desktop.closeWindows(Duration.ofSeconds(5))).isFalse();
    }
}
