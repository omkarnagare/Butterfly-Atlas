package com.butterflyatlas.app.desktop.linux;

import com.butterflyatlas.app.desktop.DesktopIntegration;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app's icon on Linux (spec §4.3, contract §1): the browser starts with our window class, which the .desktop file's
 * StartupWMClass names, so the dock groups the window under our launcher; there are no native calls and no OS events.
 */
class LinuxDesktopTest {

    private final LinuxDesktop desktop = new LinuxDesktop();

    @Test
    void theBrowserStartsWithOurWindowClass() {
        assertThat(LinuxDesktop.WINDOW_CLASS).isEqualTo("butterfly-atlas");
        assertThat(desktop.browserArguments()).containsExactly("--class=butterfly-atlas");
    }

    @Test
    void everythingElseIsLeftToTheDock() {
        desktop.start(new DesktopIntegration.Events(() -> { }, () -> { }, () -> { }));
        desktop.windowComing();
        desktop.windowConnected();

        assertThat(desktop.bringForward()).isFalse();
        assertThat(desktop.closeWindows(Duration.ofSeconds(5))).isFalse();
    }
}
