package com.butterflyatlas.app.library;

/**
 * Work a library was left with where it was used before (setup design §3): imports not finished, and exports and
 * backups running or paused. Each keeps a full path of the computer it was started on.
 */
public final class UnfinishedWork {

    /** How many of each kind there are. */
    public record Counts(int imports, int exports, int backups) {
    }

    private UnfinishedWork() {
    }
}
