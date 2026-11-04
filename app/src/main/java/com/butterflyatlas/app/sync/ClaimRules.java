package com.butterflyatlas.app.sync;

import java.time.Duration;
import java.time.Instant;

/**
 * The rules for opening a library, as pure functions (one-device design §4, §5): whether this device may open it given the
 * marker and this device's clock, and what claiming it does given the snapshot's, the marker's and the working copy's
 * generations.
 */
public final class ClaimRules {

    /** How long another device must not have been seen before Open here anyway is offered (§4). */
    public static final Duration AWAY = Duration.ofMinutes(15);

    /** Whether this device may open the library. */
    public sealed interface Access permits Open, Refused, TooSoon {
    }

    /** §4 case 1 (or case 3 once allowed): go on to the claim. */
    public record Open() implements Access {
    }

    /**
     * §4 case 2: another device has it open. {@code name} null when unknown; {@code openAnywayFrom} is {@code seen} + 15
     * minutes, and {@code offered} whether that time has come by this device's clock.
     */
    public record Refused(String name, Instant seen, Instant openAnywayFrom, boolean offered) implements Access {
    }

    /** Open here anyway asked for before {@code from} (409 {@code too_soon}). */
    public record TooSoon(Instant from) implements Access {
    }

    /** What claiming the library does (§5). */
    public enum Claim {
        /** Case 1: the marker came through before the snapshot. */
        NOT_SYNCED,
        /** Case 2: the working copy is still in step (publish at once when it has unsent changes). */
        USE_WORKING_COPY,
        /** Case 3: the working copy is out of step and has unsent changes (§7). */
        DISCARD,
        /** Case 4: copy the snapshot to the working copy. */
        COPY_DOWN
    }

    private ClaimRules() {
    }

    /**
     * Whether this device ({@code deviceId}) may open a library whose marker is {@code marker} (null: none) at {@code now};
     * {@code anyway}: Open here anyway was chosen, which opens once the other device hasn't been seen for 15 minutes.
     */
    public static Access access(Marker marker, String deviceId, Instant now, boolean anyway) {
        if (marker == null || !marker.open() || marker.names(deviceId)) {
            return new Open();
        }
        Instant from = marker.seen().plus(AWAY);
        boolean offered = !now.isBefore(from);
        if (anyway) {
            return offered ? new Open() : new TooSoon(from);
        }
        return new Refused(marker.name(), marker.seen(), from, offered);
    }

    /**
     * What claiming does: {@code marker} is the marker's generation M (0 when there is none), {@code snapshot} the
     * snapshot's L, {@code working} the working copy's G (null when this device has none) and {@code dirty} its D.
     */
    public static Claim claim(long marker, long snapshot, Long working, boolean dirty) {
        if (marker > snapshot) {
            return Claim.NOT_SYNCED;
        }
        if (working != null && working == snapshot) {
            return Claim.USE_WORKING_COPY;
        }
        if (working != null && dirty) {
            return Claim.DISCARD;
        }
        return Claim.COPY_DOWN;
    }

    /** Whole minutes from {@code seen} to {@code now}; a {@code seen} in the future counts as 0. */
    public static long minutesAgo(Instant seen, Instant now) {
        return Math.max(0, Duration.between(seen, now).toMinutes());
    }
}
