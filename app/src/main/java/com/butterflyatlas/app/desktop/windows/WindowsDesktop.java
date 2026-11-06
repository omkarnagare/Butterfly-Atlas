package com.butterflyatlas.app.desktop.windows;

import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;
import com.butterflyatlas.app.desktop.BrowserWindows;
import com.butterflyatlas.app.desktop.DesktopIntegration;
import com.butterflyatlas.app.desktop.OnceLog;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The app's icon on Windows (spec §4.2): the browser's windows get our taskbar properties, so they group under the
 * pinned or Start-menu shortcut (which carry {@link #APP_ID}) and pinning a window pins {@code Butterfly Atlas.exe}, not
 * the browser. They are set as soon as a window exists: after a window is coming (a browser start, or a key handed to a
 * second launch) the browser process's windows are looked for every {@link #POLL_MILLIS} ms for up to
 * {@link #POLL_TRIES} tries, until one is set; and again each time a window's page connects, at once and
 * {@link #AGAIN_MILLIS} ms later, because the browser sets its own properties again when the page's icon loads.
 *
 * <p>Only for the installed app ({@code jpackage.app-path} set: a relaunch command without our program would pin the
 * browser again) and in window mode (in the default-browser fallback the windows are the user's own browser). The work
 * runs on a thread of its own ({@link Scheduler}), never on the caller's. JNA that will not load is logged once and then
 * nothing more is tried; a native failure is logged once per kind and the next window tries again; nothing throws. There
 * are no reopen or quit events on Windows: closing the window is the only quit, as before.
 */
public final class WindowsDesktop implements DesktopIntegration {

    /** The AppUserModelID; both {@code [Icons]} lines of the Inno Setup script carry it (checked by PackageApp). */
    static final String APP_ID = "WeAreNaturalists.ButterflyAtlas";

    static final long POLL_MILLIS = 100;
    static final int POLL_TRIES = 50;
    static final long AGAIN_MILLIS = 2000;

    /** Runs a task after a delay, on a thread of its own. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, long delayMillis);
    }

    private final TaskbarWindows taskbar;
    private final Supplier<OptionalLong> pid;
    private final Supplier<WindowLauncher.Mode> mode;
    private final Supplier<String> appPath;
    private final Scheduler scheduler;
    private final OnceLog log;
    private volatile boolean unavailable;

    /**
     * With what sets the properties, the browser process's ID (from {@link BrowserWindows}), the mode, the installed
     * app's program ({@code jpackage.app-path}; null in a development run), where the work runs, and the log.
     */
    public WindowsDesktop(TaskbarWindows taskbar, Supplier<OptionalLong> pid, Supplier<WindowLauncher.Mode> mode,
                          Supplier<String> appPath, Scheduler scheduler, AtlasLog log) {
        this.taskbar = taskbar;
        this.pid = pid;
        this.mode = mode;
        this.appPath = appPath;
        this.scheduler = scheduler;
        this.log = new OnceLog(log);
    }

    /** The real one: the shell's calls ({@link WindowsNative}) on a daemon thread of its own. */
    public static WindowsDesktop system(BrowserWindows windows, Supplier<WindowLauncher.Mode> mode, Supplier<String> appPath,
                                        AtlasLog log) {
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "atlas-taskbar");
            t.setDaemon(true);
            return t;
        });
        return new WindowsDesktop(new WindowsNative(), windows::pid, mode, appPath,
                (task, delay) -> thread.schedule(task, delay, TimeUnit.MILLISECONDS), log);
    }

    @Override
    public List<String> browserArguments() {
        return List.of();
    }

    @Override
    public void start(Events events) {
        // no reopen, Dock menu or quit events on Windows
    }

    @Override
    public void windowComing() {
        if (program() != null) {
            schedule(() -> poll(1), POLL_MILLIS);
        }
    }

    @Override
    public void windowConnected() {
        if (program() != null) {
            schedule(this::adopt, 0);
            schedule(this::adopt, AGAIN_MILLIS);
        }
    }

    @Override
    public boolean bringForward() {
        return false; // the taskbar does it
    }

    @Override
    public boolean closeWindows(Duration wait) {
        return false; // not used on Windows
    }

    private void poll(int attempt) {
        if (adopt() > 0 || attempt >= POLL_TRIES) {
            return;
        }
        schedule(() -> poll(attempt + 1), POLL_MILLIS);
    }

    /** Sets the properties on the browser process's windows now; how many were set (0 on any failure). */
    private int adopt() {
        Path program = program();
        if (program == null) {
            return 0;
        }
        OptionalLong browser;
        try {
            browser = pid.get();
        } catch (RuntimeException e) {
            log.warn("pid", "Butterfly Atlas could not tell which browser process shows its windows.", e);
            return 0;
        }
        if (browser.isEmpty()) {
            log.info("no-process", "The taskbar properties were not set: no browser process is known for the windows.");
            return 0;
        }
        try {
            return taskbar.adopt(browser.getAsLong(), program);
        } catch (LinkageError e) {
            unavailable = true;
            log.warn("jna", "Butterfly Atlas could not load its native calls (JNA); the windows keep the browser's own"
                    + " taskbar button.", e);
        } catch (RuntimeException e) {
            log.warn("adopt", "Butterfly Atlas could not give the window its taskbar properties.", e);
        }
        return 0;
    }

    /** The installed program when the properties should be set: an installed app, in window mode, with JNA; else null. */
    private Path program() {
        if (unavailable || mode.get() != WindowLauncher.Mode.WINDOW) {
            return null;
        }
        String path = appPath.get();
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private void schedule(Runnable task, long delayMillis) {
        try {
            scheduler.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    log.warn("task", "The taskbar properties could not be set.", e);
                }
            }, delayMillis);
        } catch (RuntimeException e) {
            log.warn("schedule", "The taskbar properties could not be scheduled.", e);
        }
    }
}
