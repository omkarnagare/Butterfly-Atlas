package com.butterflyatlas.app.desktop;

import java.time.Duration;
import java.util.List;

/**
 * The one interface the rest of the app uses for the app's icon in the Dock, the taskbar or the launcher (spec §3.1,
 * contract §4): a small OS-specific piece behind it per OS, chosen by {@link DesktopIntegrations#forThisOs}. Nothing
 * outside {@code desktop} checks which OS it runs on for this, and no method throws: a native call that fails, or
 * JNA that will not load, is logged once and the icon then behaves as it did before (spec §3.4).
 */
public interface DesktopIntegration {

    /**
     * The OS's own events, which {@code AppStart} supplies: the icon clicked, New window (the Dock menu) and quit (the
     * Dock, log-out, shut-down). Each runs on a thread of its own, never on AppKit's.
     */
    record Events(Runnable iconClicked, Runnable newWindow, Runnable quit) {
    }

    /** Extra arguments for the browser, after the usual ones (Linux: {@code --class=butterfly-atlas}; others: none). */
    List<String> browserArguments();

    /** Hooks up the OS's own events (macOS: reopen, foreground, the Dock menu, quit; others: nothing). */
    void start(Events events);

    /** A window is on its way: the browser was started, or a launch key was handed to a second launch. */
    void windowComing();

    /** A window's page has connected (its stream opened). */
    void windowConnected();

    /** Brings our windows to the front; false when it could not (no browser process known, a native failure). */
    boolean bringForward();

    /**
     * Asks the browser to close our windows gracefully and waits up to {@code wait}; false when none is known or it is
     * still running after the wait. Never force-kills.
     */
    boolean closeWindows(Duration wait);
}
