package com.butterflyatlas.app.window.desktop;

import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Which desktop piece runs (contract §4): the only place {@code os.name} is read for the app's icon. */
class DesktopIntegrationsTest {

    @TempDir
    Path config;

    private AtlasLog log;
    private BrowserWindows windows;

    @BeforeEach
    void setUp() {
        log = AtlasLog.open(config, new TestClock());
        windows = new BrowserWindows(Optional::empty, config.resolve("browser_profile"), ProfileHolder.NONE,
                pid -> Optional.empty(), System::nanoTime, log);
    }

    private DesktopIntegration forOs(String os, boolean noBrowser, boolean headless) {
        return DesktopIntegrations.forThisOs(os, noBrowser, headless, windows, log, () -> WindowLauncher.Mode.WINDOW,
                () -> null);
    }

    @Test
    void withoutABrowserWindowOrOnAHeadlessSystemNothingIsHookedUp() {
        for (String os : new String[] {"Windows 11", "Mac OS X", "Linux"}) {
            assertThat(forOs(os, true, false)).as(os + ", -Datlas.noBrowser").isInstanceOf(NoDesktop.class);
            assertThat(forOs(os, false, true)).as(os + ", headless").isInstanceOf(NoDesktop.class);
        }
    }

    @Test
    void windowsGetsItsTaskbarPieceAndFindsTheProfilesBrowserFromCommandLines() {
        assertThat(forOs("Windows 11", false, false))
                .isInstanceOf(com.butterflyatlas.app.window.desktop.windows.WindowsDesktop.class);
        assertThat(forOs("Windows Server 2022", false, false))
                .isInstanceOf(com.butterflyatlas.app.window.desktop.windows.WindowsDesktop.class);
        assertThat(DesktopIntegrations.profileHolder("Windows 10", log))
                .isInstanceOf(com.butterflyatlas.app.window.desktop.windows.WindowsNative.class);
    }

    @Test
    void anOsNotListedGetsNothing() {
        assertThat(forOs("FreeBSD", false, false)).isInstanceOf(NoDesktop.class);
        assertThat(forOs("SunOS", false, false)).isInstanceOf(NoDesktop.class);
        assertThat(forOs(null, false, false)).isInstanceOf(NoDesktop.class);
    }

    @Test
    void noDesktopDoesNothingAndSaysItCouldNot() {
        NoDesktop none = new NoDesktop();
        none.start(new DesktopIntegration.Events(() -> { }, () -> { }, () -> { }));
        none.windowComing();
        none.windowConnected();

        assertThat(none.browserArguments()).isEmpty();
        assertThat(none.bringForward()).isFalse();
        assertThat(none.closeWindows(Duration.ofSeconds(5))).isFalse();
    }

    @Test
    void theProfileIsReadFromSingletonLockOffWindows() {
        assertThat(DesktopIntegrations.profileHolder("Mac OS X", log)).isNotSameAs(ProfileHolder.NONE);
        assertThat(DesktopIntegrations.profileHolder("Linux", log)).isNotSameAs(ProfileHolder.NONE);
    }
}
