package com.butterflyatlas.app.desktop.linux;

import com.butterflyatlas.app.desktop.DesktopIntegration;

import java.time.Duration;
import java.util.List;

/**
 * The app's icon on Linux (spec §4.3): the browser starts with {@code --class=}{@value #WINDOW_CLASS}, which sets its
 * windows' WM_CLASS on X11 (and, expected, the app_id on Wayland). The {@code .desktop} file the {@code .deb} and
 * {@code .rpm} install says {@code StartupWMClass=}{@value #WINDOW_CLASS} (checked by PackageApp's check-linux), so the
 * dock groups the window under our launcher, a click on it brings the window forward (the dock does that), and pinning
 * it pins Butterfly Atlas. No native code and no OS events: the dock does the rest.
 */
public final class LinuxDesktop implements DesktopIntegration {

    /** The window class; {@code StartupWMClass} in {@code packaging/linux/Butterfly Atlas.desktop} carries it. */
    static final String WINDOW_CLASS = "butterfly-atlas";

    @Override
    public List<String> browserArguments() {
        return List.of("--class=" + WINDOW_CLASS);
    }

    @Override
    public void start(Events events) {
        // no Dock events on Linux
    }

    @Override
    public void windowComing() {
        // nothing to do
    }

    @Override
    public void windowConnected() {
        // nothing to do
    }

    @Override
    public boolean bringForward() {
        return false; // the dock does it
    }

    @Override
    public boolean closeWindows(Duration wait) {
        return false; // not used on Linux
    }
}
