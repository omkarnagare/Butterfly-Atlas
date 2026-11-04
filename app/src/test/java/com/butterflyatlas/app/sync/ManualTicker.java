package com.butterflyatlas.app.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A {@link Ticker} a test runs by hand: {@link #runDue} runs what is due by the (fake) clock, in order. */
public final class ManualTicker implements Ticker {

    private record Task(Runnable work, Instant due, long order) {
    }

    private final Clock clock;
    private final List<Task> tasks = new ArrayList<>();
    private long made;

    public ManualTicker(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Later schedule(Runnable task, Duration delay) {
        Task t = new Task(task, clock.instant().plus(delay), made++);
        tasks.add(t);
        return () -> {
            synchronized (this) {
                tasks.remove(t);
            }
        };
    }

    /** How many tasks wait. */
    public synchronized int waiting() {
        return tasks.size();
    }

    /** Runs every task due now (and those they schedule that are due too). */
    public void runDue() {
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream().filter(t -> !t.due().isAfter(clock.instant()))
                        .min((x, y) -> x.due().equals(y.due()) ? Long.compare(x.order(), y.order()) : x.due().compareTo(y.due()))
                        .orElse(null);
                if (next == null) {
                    return;
                }
                tasks.remove(next);
            }
            next.work().run();
        }
    }
}
