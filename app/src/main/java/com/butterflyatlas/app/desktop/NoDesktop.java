package com.butterflyatlas.app.desktop;

import java.time.Duration;
import java.util.List;

/**
 * The desktop piece that does nothing (spec §3): for the tests, for {@code -Datlas.noBrowser}, on a headless system and
 * on an OS not listed. The icon then behaves as it did before this piece existed. Not final: a test's fake extends it and
 * overrides what it means to watch.
 */
public class NoDesktop implements DesktopIntegration {

    @Override
    public List<String> browserArguments() {
        return List.of();
    }

    @Override
    public void start(Events events) {
        // nothing to hook up
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
    public boolean windowsGone() {
        return false;
    }

    @Override
    public boolean bringForward() {
        return false;
    }

    @Override
    public boolean closeWindows(Duration wait) {
        return false;
    }
}
