package com.butterflyatlas.app.desktop.mac;

import com.butterflyatlas.app.desktop.DesktopIntegration;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app's icon on macOS (spec §4.1, contract §4), with fakes for the Dock and the native calls: the Dock's events go to
 * the app's on a thread of their own; bringing forward activates the browser process, and says it could not when its
 * windows are all minimised (so a new window opens: the reopen event would open a blank one); Quit asks the browser to quit and waits, never force-kills, and never in tab mode;
 * a native failure is logged once and nothing throws.
 */
class MacDesktopTest {

    @TempDir
    Path config;

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private final List<Runnable> handedOff = new ArrayList<>();
    private final AtomicReference<WindowLauncher.Mode> mode = new AtomicReference<>(WindowLauncher.Mode.WINDOW);
    private OptionalLong pid = OptionalLong.of(777);
    private MacApps.Windows windows = new MacApps.Windows(1, 1);
    private boolean exits = true;
    private RuntimeException fails;
    private Error jna;
    private MacDesktop.Hooks hooks;
    private AtlasLog log;
    private MacDesktop desktop;

    private final MacApps apps = new MacApps() {
        @Override
        public boolean activate(long processId) {
            failIfAsked();
            calls.add("activate " + processId);
            return true;
        }

        @Override
        public boolean terminate(long processId) {
            failIfAsked();
            calls.add("terminate " + processId);
            return true;
        }

        @Override
        public Windows windows(long processId) {
            failIfAsked();
            return windows;
        }
    };

    private void failIfAsked() {
        if (jna != null) {
            throw jna;
        }
        if (fails != null) {
            throw fails;
        }
    }

    @BeforeEach
    void setUp() {
        log = AtlasLog.open(config, new TestClock());
        desktop = new MacDesktop(given -> hooks = given, apps, () -> pid, mode::get, (processId, wait) -> {
            calls.add("wait " + processId + " " + wait.toSeconds() + "s");
            return exits;
        }, handedOff::add, log);
        desktop.start(new DesktopIntegration.Events(() -> {
            calls.add("icon clicked");
            threads.add(Thread.currentThread().getName());
        }, () -> calls.add("new window"), () -> calls.add("quit")));
    }

    private void runHandedOff() {
        List<Runnable> now = new ArrayList<>(handedOff);
        handedOff.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void theDocksEventsReachTheAppsOnAThreadOfTheirOwnAndReturnAtOnce() {
        hooks.reopened().run();
        hooks.foreground().run();
        hooks.newWindow().run();
        assertThat(calls).as("nothing runs on the Dock's thread").isEmpty();

        runHandedOff();

        assertThat(calls).containsExactly("icon clicked", "icon clicked", "new window");
    }

    @Test
    void theRealHandOffRunsOnAThreadOfItsOwn() throws Exception {
        MacDesktop real = new MacDesktop(given -> hooks = given, apps, () -> pid, mode::get, (p, w) -> true,
                MacDesktop.ownThread(), log);
        real.start(new DesktopIntegration.Events(() -> threads.add(Thread.currentThread().getName()), () -> { },
                () -> { }));

        hooks.reopened().run();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (threads.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(threads).hasSize(1);
        assertThat(threads.get(0)).isNotEqualTo(Thread.currentThread().getName()).startsWith("atlas-dock");
    }

    @Test
    void aQuitFromTheDockGoesToTheAppAndLaterSwitchesToTheAppAreNotClicks() {
        hooks.quit().run();
        hooks.foreground().run();
        runHandedOff();

        assertThat(calls).containsExactly("quit");
    }

    @Test
    void bringingForwardActivatesTheBrowserProcess() {
        assertThat(desktop.bringForward()).isTrue();

        assertThat(calls).containsExactly("activate 777");
    }

    @Test
    void withEveryWindowMinimisedItSaysItCouldNotSoANewWindowOpens() throws Exception {
        windows = new MacApps.Windows(2, 0);

        assertThat(desktop.bringForward()).isFalse();

        assertThat(calls).isEmpty();
        assertThat(Files.readString(log.file())).contains("all minimised; a new window opens instead");
    }

    @Test
    void withNoWindowListedYetTheBrowserIsActivated() {
        windows = new MacApps.Windows(0, 0); // a window on its way

        assertThat(desktop.bringForward()).isTrue();

        assertThat(calls).containsExactly("activate 777");
    }

    @Test
    void withNoBrowserProcessKnownNothingIsBroughtForward() {
        pid = OptionalLong.empty();

        assertThat(desktop.bringForward()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void quitAsksTheBrowserToQuitAndWaitsForIt() {
        assertThat(desktop.closeWindows(Duration.ofSeconds(5))).isTrue();

        assertThat(calls).containsExactly("terminate 777", "wait 777 5s");
    }

    @Test
    void aBrowserStillRunningAfterTheWaitIsLeftRunning() {
        exits = false;

        assertThat(desktop.closeWindows(Duration.ofSeconds(5))).isFalse();
        assertThat(calls).containsExactly("terminate 777", "wait 777 5s");
    }

    @Test
    void inTabModeTheUsersOwnBrowserIsNeverAskedToQuit() {
        mode.set(WindowLauncher.Mode.TAB);

        assertThat(desktop.closeWindows(Duration.ofSeconds(5))).isFalse();
        assertThat(desktop.bringForward()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void aNativeFailureIsLoggedOnceAndNothingThrows() throws Exception {
        fails = new IllegalStateException("objc said no");

        assertThat(desktop.bringForward()).isFalse();
        assertThat(desktop.bringForward()).isFalse();
        assertThat(desktop.closeWindows(Duration.ofSeconds(1))).isFalse();

        String text = Files.readString(log.file());
        assertThat(text.split("could not bring its windows forward", -1)).hasSize(2);
        assertThat(text).contains("could not ask the browser to close its windows");
    }

    @Test
    void jnaThatWillNotLoadIsLoggedOnceAndThenNothingMoreIsTried() throws Exception {
        jna = new UnsatisfiedLinkError("no jnidispatch");

        assertThat(desktop.bringForward()).isFalse();
        jna = null;
        assertThat(desktop.bringForward()).isFalse();
        assertThat(desktop.closeWindows(Duration.ofSeconds(1))).isFalse();

        assertThat(calls).isEmpty();
        String text = Files.readString(log.file());
        assertThat(text.split("could not load its native calls", -1)).hasSize(2);
    }

    @Test
    void aDockThatCannotBeHookedUpIsLoggedAndTheAppCarriesOn() throws Exception {
        MacDesktop broken = new MacDesktop(given -> {
            throw new UnsupportedOperationException("no Dock");
        }, apps, () -> pid, mode::get, (p, w) -> true, handedOff::add, log);

        broken.start(new DesktopIntegration.Events(() -> { }, () -> { }, () -> { }));

        assertThat(Files.readString(log.file())).contains("could not hook up the Dock");
        assertThat(broken.browserArguments()).isEmpty();
    }
}
