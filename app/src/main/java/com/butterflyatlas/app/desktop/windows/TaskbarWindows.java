package com.butterflyatlas.app.desktop.windows;

import java.nio.file.Path;

/**
 * Gives a browser process's windows our taskbar properties (contract §4); {@link WindowsNative} does it with the shell's
 * calls, a test with a fake.
 */
@FunctionalInterface
public interface TaskbarWindows {

    /**
     * For each visible top-level window of {@code pid} with Chrome's and Edge's window class, sets the AppUserModelID, the
     * relaunch command ({@code appPath}, quoted), its display name and its icon; how many windows were set. Throws when
     * the calls fail (an {@link Error} when JNA will not load).
     */
    int adopt(long pid, Path appPath);

    /**
     * How many visible top-level windows {@code pid} has with Chrome's and Edge's window class; -1 when this piece cannot
     * count them. Throws when the calls fail.
     */
    default int windowCount(long pid) {
        return -1;
    }
}
