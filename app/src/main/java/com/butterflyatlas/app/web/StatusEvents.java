package com.butterflyatlas.app.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Windows kept in step (multi-window design §6, contract §2, audit A11): each window's stream is told {@code event: status}
 * once as it opens, and again whenever what {@code /api/status} would answer differs from what that stream was last told.
 * {@link #check} is run every second by the session's routes; the status is built from memory, and is not even built
 * while no stream is open.
 */
final class StatusEvents {

    /** A window's stream: {@link #status} sends it one {@code event: status}. */
    interface Stream {
        void status();
    }

    private static final Logger LOG = LoggerFactory.getLogger(StatusEvents.class);
    /** Stands for a status that could not be read: never equal to a real one. */
    private static final String UNREAD = "";

    private final Supplier<String> status;
    /** Whether every stream is told at each check, changed or not (a library change runs: contract §2). */
    private final BooleanSupplier always;
    /** The open streams, each with the status it was last told of. */
    private final Map<Stream, String> told = new ConcurrentHashMap<>();

    /** {@code status}: the status's answer as JSON. */
    StatusEvents(Supplier<String> status) {
        this(status, () -> false);
    }

    /**
     * As above; while {@code always} holds every open stream is told at every check (at least once a second while a
     * library change runs, so a window that missed one still re-reads the status).
     */
    StatusEvents(Supplier<String> status, BooleanSupplier always) {
        this.status = status;
        this.always = always;
    }

    /** A stream opened: it is told at once (a window that slept and reconnects may have missed changes). */
    void opened(Stream stream) {
        told.put(stream, read());
        try {
            stream.status();
        } catch (RuntimeException e) {
            LOG.warn("Could not tell a window's stream of the status; it is dropped", e);
            told.remove(stream);
        }
    }

    void closed(Stream stream) {
        told.remove(stream);
    }

    /** The number of open streams. */
    int open() {
        return told.size();
    }

    /**
     * Tells each open stream whose last status differs from the status now (each of them while {@code always} holds);
     * nothing when it cannot be read.
     */
    void check() {
        if (told.isEmpty()) {
            return;
        }
        String now = read();
        if (UNREAD.equals(now)) {
            return;
        }
        boolean every = always.getAsBoolean();
        for (Map.Entry<Stream, String> open : told.entrySet()) {
            if (!every && Objects.equals(open.getValue(), now)) {
                continue;
            }
            try {
                open.getKey().status();
                open.setValue(now);
            } catch (RuntimeException e) {
                LOG.warn("Could not tell a window's stream of the status; it is dropped", e);
                told.remove(open.getKey()); // the others are still told
            }
        }
    }

    private String read() {
        try {
            return status.get();
        } catch (RuntimeException e) {
            LOG.debug("Could not read the status for the windows' streams", e);
            return UNREAD;
        }
    }
}
