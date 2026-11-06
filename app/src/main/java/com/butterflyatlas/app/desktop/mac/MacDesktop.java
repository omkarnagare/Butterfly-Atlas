package com.butterflyatlas.app.desktop.mac;

import com.butterflyatlas.app.desktop.BrowserWindows;
import com.butterflyatlas.app.desktop.DesktopIntegration;
import com.butterflyatlas.app.desktop.OnceLog;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;

import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Taskbar;
import java.awt.desktop.AppForegroundEvent;
import java.awt.desktop.AppForegroundListener;
import java.awt.desktop.AppReopenedListener;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The app's icon on macOS (spec §4.1). The installed app has a Dock tile of its own (Main no longer hides it), and this
 * piece answers it:
 * <ul>
 *   <li>a click on the tile, or double-clicking the {@code .app} while it runs (the reopen event), and switching to the
 *   app with Cmd-Tab or Mission Control (it becomes active with no window of its own) are both the icon clicked; once a
 *   Quit from the Dock or the OS has come, a switch to the app is no longer one;</li>
 *   <li>the tile's menu has one item, {@value #NEW_WINDOW}: a new window;</li>
 *   <li>Quit from the tile's menu or Java's menu bar, log-out and shut-down go to the app's quit, which never asks;</li>
 *   <li>{@link #bringForward} activates the browser process by its ID, with all its windows; our app is active at that
 *   moment (the user just clicked or switched to it), so macOS's cooperative activation lets it hand activation over.
 *   Activating does not bring a minimised window back, and the standard reopen event does not either: the real-window
 *   check found that Chrome answers it with a new, blank tabbed window, since its only windows are app windows. So when
 *   the browser has windows but none on screen (all minimised), it returns false and a new Butterfly Atlas window opens
 *   in their place (spec §4.1); the minimised one stays in the Dock;</li>
 *   <li>{@link #closeWindows} asks the browser process to quit, as its own Quit does, and waits; it never force-kills, and
 *   in the default-browser fallback it asks nothing (those windows are the user's own browser).</li>
 * </ul>
 * Every event from AppKit is handed to a thread of its own and its handler returns at once, so the Dock never shows the
 * app as not responding. A Dock that cannot be hooked up, JNA that will not load or a native call that fails is logged
 * once and the icon then behaves as before; nothing throws.
 *
 * <p>The quit is not confirmed to macOS with {@code performQuit}: the app's own quit ends the process with
 * {@code System.exit}, whose shutdown hooks close the library first, and that exit is the answer macOS waits for.
 */
public final class MacDesktop implements DesktopIntegration {

    /** The one item of the Dock tile's menu. */
    static final String NEW_WINDOW = "New window";

    /** What the Dock tells: the four handlers this piece gives it. */
    public record Hooks(Runnable reopened, Runnable foreground, Runnable newWindow, Runnable quit) {
    }

    /** Hooks the handlers up to the Dock; throws when it cannot. */
    @FunctionalInterface
    public interface Dock {
        void hook(Hooks hooks);
    }

    /** Waits up to {@code wait} for a process to exit; true when it has (or was not running). */
    @FunctionalInterface
    public interface Exits {
        boolean within(long pid, Duration wait);

        /** This system's processes. */
        Exits SYSTEM = (pid, wait) -> {
            try {
                var handle = ProcessHandle.of(pid);
                if (handle.isEmpty() || !handle.get().isAlive()) {
                    return true;
                }
                handle.get().onExit().get(Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
                return false;
            }
        };
    }

    private final Dock dock;
    private final MacApps apps;
    private final Supplier<OptionalLong> pid;
    private final Supplier<WindowLauncher.Mode> mode;
    private final Exits exits;
    private final Executor handOff;
    private final OnceLog log;
    private volatile boolean quitAsked;
    private volatile boolean unavailable;

    /**
     * With the Dock, the native calls, the browser process's ID (from {@link BrowserWindows}), the mode, how a process's
     * exit is waited for, where the Dock's events are handed to, and the log.
     */
    public MacDesktop(Dock dock, MacApps apps, Supplier<OptionalLong> pid, Supplier<WindowLauncher.Mode> mode, Exits exits,
                      Executor handOff, AtlasLog log) {
        this.dock = dock;
        this.apps = apps;
        this.pid = pid;
        this.mode = mode;
        this.exits = exits;
        this.handOff = handOff;
        this.log = new OnceLog(log);
    }

    /** The real one: Java's Dock events ({@code java.awt.desktop}, {@code java.awt.Taskbar}) and {@link MacNative}. */
    public static MacDesktop system(BrowserWindows windows, Supplier<WindowLauncher.Mode> mode, AtlasLog log) {
        return new MacDesktop(MacDesktop::hookSystem, new MacNative(), windows::pid, mode, Exits.SYSTEM, ownThread(), log);
    }

    /** Runs each task on a new daemon thread of its own. */
    static Executor ownThread() {
        return task -> {
            Thread thread = new Thread(task, "atlas-dock");
            thread.setDaemon(true);
            thread.start();
        };
    }

    /** Java's own Dock events: reopen, foreground, the quit handler and the tile's menu. */
    private static void hookSystem(Hooks hooks) {
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener((AppReopenedListener) event -> hooks.reopened().run());
        }
        if (desktop.isSupported(Desktop.Action.APP_EVENT_FOREGROUND)) {
            desktop.addAppEventListener(new AppForegroundListener() {
                @Override
                public void appRaisedToForeground(AppForegroundEvent event) {
                    hooks.foreground().run();
                }

                @Override
                public void appMovedToBackground(AppForegroundEvent event) {
                    // nothing to do
                }
            });
        }
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler((event, response) -> hooks.quit().run());
        }
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.MENU)) {
            PopupMenu menu = new PopupMenu();
            MenuItem item = new MenuItem(NEW_WINDOW);
            item.addActionListener(event -> hooks.newWindow().run());
            menu.add(item);
            Taskbar.getTaskbar().setMenu(menu);
        }
    }

    @Override
    public List<String> browserArguments() {
        return List.of();
    }

    @Override
    public void start(Events events) {
        try {
            dock.hook(new Hooks(() -> hand("click", events.iconClicked()), () -> {
                if (!quitAsked) {
                    hand("click", events.iconClicked());
                }
            }, () -> hand("new-window", events.newWindow()), () -> {
                quitAsked = true;
                hand("quit", events.quit());
            }));
        } catch (RuntimeException | LinkageError e) {
            log.warn("dock", "Butterfly Atlas could not hook up the Dock; its tile behaves as before.", e);
        }
    }

    /** Hands {@code task} to a thread of its own; the Dock's thread returns at once. */
    private void hand(String kind, Runnable task) {
        try {
            handOff.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("event-" + kind, "Butterfly Atlas could not act on an event from the Dock.", e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("hand-" + kind, "Butterfly Atlas could not act on an event from the Dock.", e);
        }
    }

    @Override
    public void windowComing() {
        // nothing to do on macOS
    }

    @Override
    public void windowConnected() {
        // nothing to do on macOS
    }

    @Override
    public boolean bringForward() {
        OptionalLong browser = browser("forward");
        if (browser.isEmpty()) {
            return false;
        }
        long process = browser.getAsLong();
        try {
            MacApps.Windows windows = apps.windows(process);
            if (windows.all() > 0 && windows.onScreen() == 0) {
                log.info("minimised", "The browser's windows are all minimised; a new window opens instead.");
                return false;
            }
            return apps.activate(process);
        } catch (LinkageError e) {
            jnaFailed(e);
        } catch (RuntimeException e) {
            log.warn("forward", "Butterfly Atlas could not bring its windows forward.", e);
        }
        return false;
    }

    @Override
    public boolean closeWindows(Duration wait) {
        OptionalLong browser = browser("close");
        if (browser.isEmpty()) {
            return false;
        }
        long process = browser.getAsLong();
        try {
            if (!apps.terminate(process)) {
                log.info("terminate", "The browser did not take the request to quit; its windows stay open.");
                return false;
            }
        } catch (LinkageError e) {
            jnaFailed(e);
            return false;
        } catch (RuntimeException e) {
            log.warn("close", "Butterfly Atlas could not ask the browser to close its windows.", e);
            return false;
        }
        return exits.within(process, wait);
    }

    /** The browser process to act on: none in tab mode, without JNA, or when none is known. */
    private OptionalLong browser(String why) {
        if (unavailable || mode.get() != WindowLauncher.Mode.WINDOW) {
            return OptionalLong.empty();
        }
        OptionalLong browser;
        try {
            browser = pid.get();
        } catch (RuntimeException e) {
            log.warn("pid", "Butterfly Atlas could not tell which browser process shows its windows.", e);
            return OptionalLong.empty();
        }
        if (browser.isEmpty()) {
            log.info("no-process-" + why, "No browser process is known for the windows; the Dock tile cannot act on them.");
        }
        return browser;
    }

    private void jnaFailed(LinkageError e) {
        unavailable = true;
        log.warn("jna", "Butterfly Atlas could not load its native calls (JNA); the Dock tile opens new windows only.", e);
    }
}
