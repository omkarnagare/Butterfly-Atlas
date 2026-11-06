package com.butterflyatlas.app.desktop;

import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A click on the app's icon (spec §4.1, §10; contract §4): held until the app is ready (one at most); while it quits, it
 * starts again after the quit, once; in tab mode a new tab; with a window open or one on its way, brought forward (a new
 * window when that fails); otherwise a new window; two clicks within 500 ms act once.
 */
class IconClicksTest {

    @TempDir
    Path config;

    private final List<String> did = new ArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<WindowLauncher.Mode> mode = new AtomicReference<>(WindowLauncher.Mode.WINDOW);
    private final AtomicInteger streams = new AtomicInteger();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean forwards = new AtomicBoolean(true);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private IconClicks clicks;

    @BeforeEach
    void setUp() {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        clicks = new IconClicks(closing::get, mode::get, streams::get, pending::get, () -> {
            did.add("forward");
            return forwards.get();
        }, () -> did.add("new window"), () -> did.add("relaunch"), nanos::get, Runnable::run, log);
        clicks.ready();
    }

    private void later(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }

    @Test
    void withNoWindowOpenAClickOpensANewWindow() {
        clicks.clicked();

        assertThat(did).containsExactly("new window");
    }

    @Test
    void withAWindowOpenAClickBringsItForward() {
        streams.set(1);

        clicks.clicked();

        assertThat(did).containsExactly("forward");
    }

    @Test
    void withAWindowOnItsWayAClickBringsItForward() {
        pending.set(true);

        clicks.clicked();

        assertThat(did).containsExactly("forward");
    }

    @Test
    void whenBringingForwardFailsANewWindowOpensInstead() {
        streams.set(1);
        forwards.set(false);

        clicks.clicked();

        assertThat(did).containsExactly("forward", "new window");
    }

    @Test
    void whenAWindowOnItsWayCannotBeBroughtForwardNoSecondWindowOpens() {
        // a click while the app starts: the browser process is not known yet, but its window is coming
        pending.set(true);
        forwards.set(false);

        clicks.clicked();

        assertThat(did).containsExactly("forward");
    }

    @Test
    void aClickHeldDuringTheStartOpensNoSecondWindow() {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        IconClicks starting = new IconClicks(closing::get, mode::get, streams::get, pending::get, () -> {
            did.add("forward");
            return false;
        }, () -> did.add("new window"), () -> did.add("relaunch"), nanos::get, Runnable::run, log);
        starting.clicked();
        pending.set(true); // the first window was launched, not connected yet

        starting.ready();

        assertThat(did).containsExactly("forward");
    }

    @Test
    void inTabModeAClickOpensANewTabEvenWithOneOpen() {
        mode.set(WindowLauncher.Mode.TAB);
        streams.set(1);

        clicks.clicked();

        assertThat(did).containsExactly("new window");
    }

    @Test
    void whileQuittingAClickStartsTheAppAgainAfterTheQuitOnce() {
        closing.set(true);

        clicks.clicked();
        later(600);
        clicks.clicked();

        assertThat(did).containsExactly("relaunch");
    }

    @Test
    void aClickBeforeTheAppIsReadyIsHeldAndActedOnOnceItIs() {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        IconClicks early = new IconClicks(closing::get, mode::get, streams::get, pending::get, () -> {
            did.add("forward");
            return true;
        }, () -> did.add("new window"), () -> did.add("relaunch"), nanos::get, Runnable::run, log);

        early.clicked();
        later(600);
        early.clicked();
        assertThat(did).isEmpty();

        pending.set(true); // the first window was launched by the time the app is ready
        early.ready();
        early.ready();

        assertThat(did).containsExactly("forward");
    }

    @Test
    void twoClicksWithin500MillisecondsActOnce() {
        clicks.clicked();
        later(499);
        clicks.clicked();
        assertThat(did).containsExactly("new window");

        later(501);
        clicks.clicked();
        assertThat(did).containsExactly("new window", "new window");
    }

    @Test
    void anActionThatThrowsIsLoggedAndTheNextClickStillWorks() {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        AtomicInteger tries = new AtomicInteger();
        IconClicks failing = new IconClicks(closing::get, mode::get, streams::get, pending::get, () -> true, () -> {
            if (tries.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            did.add("new window");
        }, () -> did.add("relaunch"), nanos::get, Runnable::run, log);
        failing.ready();

        failing.clicked();
        later(600);
        failing.clicked();

        assertThat(did).containsExactly("new window");
    }
}
