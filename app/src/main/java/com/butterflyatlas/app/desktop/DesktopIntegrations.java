package com.butterflyatlas.app.desktop;

import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import com.butterflyatlas.app.desktop.windows.WindowsDesktop;
import com.butterflyatlas.app.desktop.windows.WindowsNative;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Chooses the desktop piece for this OS (contract §4): the only place {@code os.name} is read for the app's icon.
 * {@link NoDesktop} with {@code -Datlas.noBrowser} (there is no window of ours), on a headless system and on an OS not
 * listed; {@link WindowsDesktop} on Windows. macOS and Linux get their own pieces in a later step; until then they get
 * {@link NoDesktop} too.
 */
public final class DesktopIntegrations {

    private DesktopIntegrations() {
    }

    /**
     * The piece for {@code osName}. {@code headless}: AWT is headless (the caller asks, since this package uses no AWT);
     * {@code windows}: which browser process shows our windows; {@code mode}: whether those are app windows or the
     * default browser's tabs; {@code appPath}: the installed app's program ({@code jpackage.app-path}; null in a
     * development run).
     */
    public static DesktopIntegration forThisOs(String osName, boolean noBrowser, boolean headless, BrowserWindows windows,
                                               AtlasLog log, Supplier<WindowLauncher.Mode> mode, Supplier<String> appPath) {
        if (noBrowser || headless) {
            return new NoDesktop();
        }
        return switch (os(osName)) {
            case "windows" -> WindowsDesktop.system(windows, mode, appPath, log);
            default -> new NoDesktop();
        };
    }

    /** How the browser process holding our profile is found on {@code osName}, when this copy did not start it. */
    public static ProfileHolder profileHolder(String osName, AtlasLog log) {
        return switch (os(osName)) {
            case "mac", "linux" -> ProfileHolder.singletonLock();
            case "windows" -> new WindowsNative();
            default -> ProfileHolder.NONE;
        };
    }

    private static String os(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return "windows";
        }
        if (os.startsWith("mac")) {
            return "mac";
        }
        if (os.startsWith("linux")) {
            return "linux";
        }
        return "other";
    }
}
