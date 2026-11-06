package com.butterflyatlas.app.library.sync;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs work later, on a thread of its own (tests run it by hand, with a fake clock). */
public interface Ticker {

    /** Work asked for later: it can be called off. */
    interface Later {
        void cancel();
    }

    /** Runs {@code task} once after {@code delay}. */
    Later schedule(Runnable task, Duration delay);

    /** The app's: one daemon thread, {@code atlas-sync}. */
    static Ticker real() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "atlas-sync");
            thread.setDaemon(true);
            return thread;
        });
        return (task, delay) -> {
            ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
    }
}
