package com.butterflyatlas.app;

/**
 * The states an export, a backup and a taxonomy update share, as the database keeps them and the UI reads them
 * ({@code ui/src/lib/states.js}), so the words never change.
 */
public final class JobStates {

    public static final String PENDING = "pending";
    /** A taxonomy update waiting for an import or export to finish. */
    public static final String WAITING = "waiting";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String DONE = "done";
    public static final String CANCELLED = "cancelled";

    private JobStates() {
    }
}
