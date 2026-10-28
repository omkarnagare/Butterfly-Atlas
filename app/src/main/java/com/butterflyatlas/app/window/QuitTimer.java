package com.butterflyatlas.app.window;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Closing the last window quits the app (design §5, audit 2 and 3). Each open window holds one stream; the timer counts
 * one-second {@link #tick}s with none open and quits at {@code quitTicks} (default 10). A tick that arrives late (the
 * computer slept: told by the wall clock as well as the monotonic one, because {@link System#nanoTime} does not advance
 * during sleep on macOS and Linux) restarts the count, so waking never quits the app under a window that has not reconnected yet. It starts
 * only once a stream has connected, except when a window is expected (the app opened one): then no stream within
 * {@code startTicks} (default 60) takes the default-browser fallback once, and none after as long again quits and says why.
 * {@code -Datlas.noBrowser=true} and tests expect no window, so they never quit until a window has connected.
 */
public final class QuitTimer implements AutoCloseable {

    static final long TICK_NANOS = 1_000_000_000L;

    private final int quitTicks;
    private final int startTicks;
    private final boolean expectWindow;
    private final LongSupplier nanos;
    private final LongSupplier wallMillis;
    private final Consumer<String> fallback;
    private final Consumer<String> quit;
    private int streams;
    private boolean connectedOnce;
    private int idle;
    private int waited;
    private long last = Long.MIN_VALUE;
    private long lastWall = Long.MIN_VALUE;
    private boolean quitFired;
    private ScheduledExecutorService ticker;

    /**
     * {@code fallback} and {@code quit} are told why (one sentence, for the log); both run outside the timer's lock, so
     * they may close streams.
     */
    public QuitTimer(int quitTicks, int startTicks, boolean expectWindow, LongSupplier nanos, Consumer<String> fallback,
                     Consumer<String> quit) {
        this(quitTicks, startTicks, expectWindow, nanos, System::currentTimeMillis, fallback, quit);
    }

    /** With the wall clock (epoch milliseconds) given too: a tick is late when either clock's gap is over 1.5 seconds. */
    public QuitTimer(int quitTicks, int startTicks, boolean expectWindow, LongSupplier nanos, LongSupplier wallMillis,
                     Consumer<String> fallback, Consumer<String> quit) {
        this.quitTicks = quitTicks;
        this.startTicks = startTicks;
        this.expectWindow = expectWindow;
        this.nanos = nanos;
        this.wallMillis = wallMillis;
        this.fallback = fallback;
        this.quit = quit;
    }

    /** A stream opened: a window is there, and a count in progress is cancelled. */
    public synchronized void opened() {
        streams++;
        connectedOnce = true;
        idle = 0;
    }

    /** A stream closed: the last one starts the count. */
    public synchronized void closed() {
        streams = Math.max(0, streams - 1);
        idle = 0;
    }

    public synchronized int streams() {
        return streams;
    }

    /** One second has passed (or more: a late call restarts the counts). */
    public void tick() {
        String say = null;
        String quitWhy = null;
        synchronized (this) {
            long now = nanos.getAsLong();
            long wall = wallMillis.getAsLong();
            boolean late = last != Long.MIN_VALUE && (now - last > TICK_NANOS * 3 / 2
                    || wall - lastWall > TICK_NANOS * 3 / 2 / 1_000_000L);
            last = now;
            lastWall = wall;
            if (late) {
                idle = 0; // the computer slept: nothing counted before, and this tick does not count either
                waited = 0;
                return;
            }
            if (streams > 0 || quitFired) {
                return;
            }
            if (connectedOnce) {
                if (++idle >= quitTicks) {
                    quitFired = true;
                    quitWhy = "The last window was closed.";
                }
            } else if (expectWindow) {
                waited++;
                if (waited == startTicks) {
                    say = "No window connected within " + startTicks + " seconds.";
                } else if (waited >= 2 * startTicks) {
                    quitFired = true;
                    quitWhy = "Quitting: no window opened within " + 2 * startTicks + " seconds.";
                }
            }
        }
        if (say != null) {
            fallback.accept(say);
        }
        if (quitWhy != null) {
            quit.accept(quitWhy);
        }
    }

    /** Ticks every second on a thread of its own, until {@link #close}. */
    public synchronized void start() {
        if (ticker == null) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "atlas-quit-timer");
                thread.setDaemon(true);
                return thread;
            });
            ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }
}
