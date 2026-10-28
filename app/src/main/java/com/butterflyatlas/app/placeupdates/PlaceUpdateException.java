package com.butterflyatlas.app.placeupdates;

/**
 * A step of a place update failed (5c contract §6): its transaction was rolled back, so the library is as it was before
 * the step (earlier steps of the same opening kept), and it is not opened. {@code from} and {@code to} are the step's
 * lists.
 */
public final class PlaceUpdateException extends RuntimeException {

    private final String from;
    private final String to;
    private final java.util.List<String> applied;

    /** {@code applied}: the steps applied before this one at the same opening (kept), oldest first. */
    public PlaceUpdateException(String from, String to, java.util.List<String> applied, Throwable cause) {
        super("The place names could not be updated from " + from + " to " + to + ": " + detail(cause), cause);
        this.from = from;
        this.to = to;
        this.applied = java.util.List.copyOf(applied);
    }

    /** The steps applied before this one at the same opening, oldest first: their place lists are the library's now. */
    public java.util.List<String> applied() {
        return applied;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    /** What went wrong: the innermost cause's message ({@code disk I/O error}), for the log. */
    private static String detail(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
