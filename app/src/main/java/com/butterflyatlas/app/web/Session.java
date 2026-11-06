package com.butterflyatlas.app.web;

import com.butterflyatlas.app.window.QuitTimer;
import com.butterflyatlas.app.window.SessionKeys;
import com.butterflyatlas.app.window.WindowLauncher;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * What the per-launch secret needs (contract §2): the keys, the quit timer the window streams drive, how the page was
 * opened, whether the app is quitting, the folders About shows, and how one more window is opened ({@code POST
 * /api/window}). {@code Main} supplies one; tests that do not mean to test the secret start the server without it, so
 * every request is answered as before. The desktop piece for the app's icon (app-icon contract §4) hears of windows:
 * {@code windowConnected} when a window's stream opens, {@code windowComing} when a second launch is handed a key.
 */
public record Session(SessionKeys keys, QuitTimer quit, Supplier<WindowLauncher.Mode> mode, BooleanSupplier closing,
                      Path configFolder, Path logFolder, Duration ping, NewWindow newWindow, Runnable windowConnected,
                      Runnable windowComing) {

    /**
     * Opens one more window on this copy, as a second launch does (multi-window contract §2, audit A12/A13): a fresh key, a
     * fresh redirect file, the app window or a default-browser tab. Returns once the browser has settled (at most a few
     * seconds); false when no window or tab could be opened, which is logged with why. Never quits the app.
     */
    @FunctionalInterface
    public interface NewWindow {
        boolean open();
    }

    /**
     * How often the stream says it is still there, so a closed window is noticed by a failed write: the server hears of a
     * closed connection only by writing to it, and the app quits some ten seconds after (so one second here, not the
     * contract's fifteen, which would make a quit take up to twenty-five).
     */
    public static final Duration PING = Duration.ofSeconds(1);

    /** With no desktop piece to tell of windows. */
    public Session(SessionKeys keys, QuitTimer quit, Supplier<WindowLauncher.Mode> mode, BooleanSupplier closing,
                   Path configFolder, Path logFolder, Duration ping, NewWindow newWindow) {
        this(keys, quit, mode, closing, configFolder, logFolder, ping, newWindow, () -> { }, () -> { });
    }

    public Session(SessionKeys keys, QuitTimer quit, Supplier<WindowLauncher.Mode> mode, BooleanSupplier closing,
                   Path configFolder, Path logFolder) {
        this(keys, quit, mode, closing, configFolder, logFolder, PING);
    }

    /** With no way to open another window (tests of the session itself): {@code POST /api/window} answers that it failed. */
    public Session(SessionKeys keys, QuitTimer quit, Supplier<WindowLauncher.Mode> mode, BooleanSupplier closing,
                   Path configFolder, Path logFolder, Duration ping) {
        this(keys, quit, mode, closing, configFolder, logFolder, ping, () -> false);
    }
}
