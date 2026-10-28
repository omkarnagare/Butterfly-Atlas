package com.butterflyatlas.app.imaging.heif;

import com.butterflyatlas.app.photos.PhotoProcessor;

import java.time.Duration;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The decoder programs that may run at once ({@value #AT_ONCE}, across thumbnails, display copies and import reading;
 * Phase 7b design §4), handed out by priority (audit 9): the viewer and the import's large view first, then thumbnails
 * on screen, then import reading, then catch-up; in the order asked within one priority. A caller waits at most the
 * time it gives.
 */
public final class DecoderSlots {

    /** Decoder programs at once. */
    public static final int AT_ONCE = 2;

    private record Ticket(int priority, long order) implements Comparable<Ticket> {
        @Override
        public int compareTo(Ticket other) {
            return priority != other.priority ? Integer.compare(priority, other.priority) : Long.compare(order, other.order);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Ticket> waiting = new PriorityQueue<>();
    private int free;
    private long next;

    public DecoderSlots() {
        this(AT_ONCE);
    }

    DecoderSlots(int slots) {
        this.free = slots;
    }

    /** A slot for {@code use}, waiting at most {@code wait}; false when none came in time. */
    public boolean acquire(PhotoProcessor.Use use, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        lock.lock();
        try {
            Ticket ticket = new Ticket(use.ordinal(), next++);
            waiting.add(ticket);
            try {
                while (free == 0 || waiting.peek() != ticket) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    changed.awaitNanos(left);
                }
                free--;
                return true;
            } finally {
                waiting.remove(ticket);
                changed.signalAll(); // the next in line may go now (or may have to wait for this one's slot)
            }
        } finally {
            lock.unlock();
        }
    }

    public void release() {
        lock.lock();
        try {
            free++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Waiting callers, for tests. */
    int waiting() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }
}
