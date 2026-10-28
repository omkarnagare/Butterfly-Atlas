package com.butterflyatlas.app;

/**
 * A problem to show the user in plain language. The core throws it; the web layer turns each
 * {@link Kind} into an HTTP status and error code, or uses the exception's own {@link #code()} when it has one.
 */
public final class AtlasException extends RuntimeException {

    public enum Kind {
        NOT_FOUND, INVALID, SETUP_REQUIRED, ALREADY_CONFIGURED, TOO_LARGE, UNSUPPORTED, BUSY, INTERRUPTED,
        /** Not in this state (the web layer answers 409). */
        CONFLICT,
        /** The computer will not let the app do it (403), e.g. a folder it may not open. */
        FORBIDDEN
    }

    /** A request about photos that names none. */
    public static final String NO_PHOTOS_CHOSEN = "Choose at least one photo.";
    /** A photo's preview or display copy that is still being made after the wait. */
    public static final String PHOTO_TOO_SLOW = "This photo is taking too long to open. Try again.";
    /** A photo in an import that can't be served (gone, or not a photo after all). */
    public static final String CANT_SHOW_PHOTO = "That photo can't be shown.";

    private final Kind kind;
    private final String code;

    public AtlasException(Kind kind, String message) {
        this(kind, null, message);
    }

    /** With a specific error code for the answer ({@code import_filing}), instead of the kind's own. */
    public AtlasException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    /** The specific error code, or null to use the kind's own. */
    public String code() {
        return code;
    }

    public static AtlasException notFound(String message) {
        return new AtlasException(Kind.NOT_FOUND, message);
    }

    public static AtlasException invalid(String message) {
        return new AtlasException(Kind.INVALID, message);
    }

    /** A file over the size Butterfly Atlas accepts. */
    public static AtlasException tooLarge(String message) {
        return new AtlasException(Kind.TOO_LARGE, message);
    }

    /** A file Butterfly Atlas cannot read as a photo. */
    public static AtlasException unsupported(String message) {
        return new AtlasException(Kind.UNSUPPORTED, message);
    }

    /** The sender went away part-way (a closed tab, a dropped connection); nothing to fix. */
    public static AtlasException interrupted(String message) {
        return new AtlasException(Kind.INTERRUPTED, message);
    }

    /** No photo with this id. */
    public static AtlasException noPhoto(Object id) {
        return notFound("There is no photo " + id + " in your atlas.");
    }

    /** No taxon with this id. */
    public static AtlasException noTaxon(Object id) {
        return notFound("There is no taxon " + id + " in your atlas.");
    }

    /** No library folder chosen yet (SETUP_REQUIRED). */
    public static AtlasException noLibrary() {
        return new AtlasException(Kind.SETUP_REQUIRED, "Choose where to keep your library first.");
    }

    /** Not now: a retry may work (the web layer answers 503 {@code busy}). */
    public static AtlasException busy(String message) {
        return new AtlasException(Kind.BUSY, message);
    }
}
