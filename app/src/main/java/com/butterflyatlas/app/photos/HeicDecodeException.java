package com.butterflyatlas.app.photos;

import java.io.IOException;

/**
 * A HEIC picture that was not decoded for a reason that is not the photo's fault (Phase 7b design §5, audit 3, 8, 9,
 * 13): it is never recorded as a failed thumbnail. Any other {@link IOException} from a HEIC decode is the photo's own
 * failure ("the decoder couldn't read it").
 */
public final class HeicDecodeException extends IOException {

    public enum Kind {
        /** No decoder can show HEIC photos now (missing, or still being checked). */
        UNAVAILABLE,
        /** The decoder took longer than its time; kept in memory, tried again later. */
        TIMED_OUT,
        /** No decoder slot came free in the time this use waits. */
        BUSY,
        /** The working copy could not be made: the library's drive is full or gone. */
        NO_WORKSPACE
    }

    private final Kind kind;

    public HeicDecodeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
