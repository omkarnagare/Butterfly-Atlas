package com.butterflyatlas.app.desktop.mac;

/**
 * What the macOS piece asks of another running app, by its process ID (spec §4.1); {@link MacNative} does it with
 * AppKit's and Core Graphics' calls, a test with a fake. Each method throws when the calls fail (an {@link Error} when JNA
 * will not load).
 */
public interface MacApps {

    /** How many ordinary windows a process has, and how many of them are on screen now. */
    record Windows(int all, int onScreen) {
    }

    /** Brings the process's windows to the front ({@code NSRunningApplication}, all windows); false when it could not. */
    boolean activate(long pid);

    /** Asks the process to quit, as Quit from its menu does ({@code NSRunningApplication terminate}); never force-kills. */
    boolean terminate(long pid);

    /** The process's ordinary windows ({@code CGWindowListCopyWindowInfo}, which needs no permission for this). */
    Windows windows(long pid);
}
