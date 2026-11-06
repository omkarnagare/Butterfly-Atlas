package com.butterflyatlas.app.desktop;

import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What a click on the app's icon does (spec §4.1, §10; contract §4), the same on every OS that tells the app of one
 * ({@code AppStart} gives it as {@code Events.iconClicked}):
 * <ul>
 *   <li>before the app is ready (the server up and the first window launched), the click is held, one at most, and acted
 *   on once {@link #ready} is called;</li>
 *   <li>while the app quits, it starts again once the quit is done, once however many clicks come;</li>
 *   <li>in the default-browser fallback a new tab opens, since a tab cannot be brought forward;</li>
 *   <li>with a window open, or one on its way (opened by this copy, not connected yet, within the start wait), the
 *   windows are brought forward, and a new window opens when that fails;</li>
 *   <li>otherwise a new window opens;</li>
 *   <li>a second click within {@value #TWICE_MILLIS} ms of the last one acted on is dropped (a click also makes the app
 *   active, which macOS tells as a second event).</li>
 * </ul>
 * An action that fails is logged once and the next click tries again; nothing throws.
 */
public final class IconClicks {

    /** Two clicks (or a click and a switch to the app) closer than this act once. */
    static final long TWICE_MILLIS = 500;

    private final BooleanSupplier closing;
    private final Supplier<WindowLauncher.Mode> mode;
    private final IntSupplier streams;
    private final BooleanSupplier windowPending;
    private final BooleanSupplier bringForward;
    private final Runnable newWindow;
    private final Runnable relaunchAfterQuit;
    private final LongSupplier nanos;
    private final Executor heldRuns;
    private final OnceLog log;
    private boolean ready;
    private boolean held;
    private boolean relaunching;
    private long last = Long.MIN_VALUE;

    /**
     * With whether the app is quitting, the window mode, how many windows are open (streams), whether a window is on its
     * way; the actions (bring the windows forward, false when it could not; a new window or tab; start again after the
     * quit); a clock in nanoseconds; where a held click runs once the app is ready; and the log.
     */
    public IconClicks(BooleanSupplier closing, Supplier<WindowLauncher.Mode> mode, IntSupplier streams,
                      BooleanSupplier windowPending, BooleanSupplier bringForward, Runnable newWindow,
                      Runnable relaunchAfterQuit, LongSupplier nanos, Executor heldRuns, AtlasLog log) {
        this.closing = closing;
        this.mode = mode;
        this.streams = streams;
        this.windowPending = windowPending;
        this.bringForward = bringForward;
        this.newWindow = newWindow;
        this.relaunchAfterQuit = relaunchAfterQuit;
        this.nanos = nanos;
        this.heldRuns = heldRuns;
        this.log = new OnceLog(log);
    }

    /** The icon was clicked (or the app was switched to with no window of its own); runs on the caller's thread. */
    public void clicked() {
        synchronized (this) {
            long now = nanos.getAsLong();
            if (last != Long.MIN_VALUE && now - last < TWICE_MILLIS * 1_000_000L) {
                return;
            }
            last = now;
            if (!ready) {
                held = true;
                return;
            }
        }
        act();
    }

    /** The app is ready: a click held until now is acted on, on {@code heldRuns}. Later calls do nothing. */
    public void ready() {
        boolean run;
        synchronized (this) {
            if (ready) {
                return;
            }
            ready = true;
            run = held;
            held = false;
        }
        if (run) {
            try {
                heldRuns.execute(this::act);
            } catch (RuntimeException e) {
                log.warn("held", "A click on the app's icon made while Butterfly Atlas was starting could not be acted on.",
                        e);
            }
        }
    }

    private void act() {
        try {
            if (closing.getAsBoolean()) {
                synchronized (this) {
                    if (relaunching) {
                        return;
                    }
                    relaunching = true;
                }
                relaunchAfterQuit.run();
                return;
            }
            if (mode.get() == WindowLauncher.Mode.TAB) {
                newWindow.run();
                return;
            }
            if (streams.getAsInt() > 0 || windowPending.getAsBoolean()) {
                if (bringForward.getAsBoolean()) {
                    return;
                }
            }
            newWindow.run();
        } catch (RuntimeException e) {
            log.warn("click", "A click on the app's icon could not be acted on.", e);
        }
    }
}
