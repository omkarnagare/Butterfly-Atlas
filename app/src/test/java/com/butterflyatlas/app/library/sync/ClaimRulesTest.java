package com.butterflyatlas.app.library.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The marker rules (one-device design §4) and the claim rules (§5), row by row, with the 15-minute edge. */
class ClaimRulesTest {

    private static final String ME = "me";
    private static final Instant SEEN = Instant.parse("2026-11-04T11:05:00Z");

    private static Marker open(String device, Instant seen) {
        return new Marker(device, "Omkar's MacBook Air", Marker.OPEN, seen.minus(Duration.ofHours(1)), seen, 37);
    }

    // §4 case 1: no marker, closed, or this device

    @Test
    void noMarkerOpens() {
        assertThat(ClaimRules.access(null, ME, SEEN, false)).isInstanceOf(ClaimRules.Open.class);
    }

    @Test
    void aClosedMarkerOfAnotherDeviceOpens() {
        Marker closed = new Marker("other", "DESKTOP-PUNE", Marker.CLOSED, SEEN, SEEN, 3);
        assertThat(ClaimRules.access(closed, ME, SEEN.plusSeconds(1), false)).isInstanceOf(ClaimRules.Open.class);
    }

    @Test
    void aMarkerOfThisDeviceOpensEvenWhenItSaysOpen() {
        assertThat(ClaimRules.access(open(ME, SEEN), ME, SEEN.plusSeconds(1), false)).isInstanceOf(ClaimRules.Open.class);
    }

    // §4 case 2: another device has it open

    @Test
    void anotherDeviceSeen14Minutes59SecondsAgoIsRefusedWithoutOpenAnyway() {
        ClaimRules.Access access = ClaimRules.access(open("other", SEEN), ME, SEEN.plus(Duration.ofSeconds(14 * 60 + 59)),
                false);

        assertThat(access).isEqualTo(new ClaimRules.Refused("Omkar's MacBook Air", SEEN,
                Instant.parse("2026-11-04T11:20:00Z"), false));
    }

    @Test
    void anotherDeviceSeenExactly15MinutesAgoIsRefusedWithOpenAnywayOffered() {
        ClaimRules.Access access = ClaimRules.access(open("other", SEEN), ME, SEEN.plus(Duration.ofMinutes(15)), false);

        assertThat(access).isEqualTo(new ClaimRules.Refused("Omkar's MacBook Air", SEEN,
                Instant.parse("2026-11-04T11:20:00Z"), true));
    }

    @Test
    void aSeenInTheFutureCountsAsJustNow() {
        Instant ahead = SEEN.plus(Duration.ofMinutes(30));
        ClaimRules.Access access = ClaimRules.access(open("other", ahead), ME, SEEN, false);

        assertThat(access).isInstanceOfSatisfying(ClaimRules.Refused.class, r -> {
            assertThat(r.offered()).isFalse();
            assertThat(r.seen()).isEqualTo(ahead); // sent as it is
            assertThat(r.openAnywayFrom()).isEqualTo(ahead.plus(Duration.ofMinutes(15)));
        });
        assertThat(ClaimRules.minutesAgo(ahead, SEEN)).isZero();
    }

    @Test
    void openAnywayIsOfferedThenWithdrawnWhenANewerSeenArrives() {
        Instant now = SEEN.plus(Duration.ofMinutes(20));
        assertThat(((ClaimRules.Refused) ClaimRules.access(open("other", SEEN), ME, now, false)).offered()).isTrue();

        Instant newer = now.minus(Duration.ofMinutes(1));
        assertThat(((ClaimRules.Refused) ClaimRules.access(open("other", newer), ME, now, false)).offered()).isFalse();
    }

    @Test
    void aMarkerWithoutANameIsAnotherDeviceWithNoName() {
        Marker unnamed = new Marker("other", null, Marker.OPEN, SEEN, SEEN, 1);
        assertThat(((ClaimRules.Refused) ClaimRules.access(unnamed, ME, SEEN, false)).name()).isNull();
    }

    // §4 case 3: Open here anyway

    @Test
    void openAnywayAfter15MinutesOpensAsIfTheMarkerWereClosed() {
        assertThat(ClaimRules.access(open("other", SEEN), ME, SEEN.plus(Duration.ofMinutes(15)), true))
                .isInstanceOf(ClaimRules.Open.class);
    }

    @Test
    void openAnywayBeforeTheTimeIsTooSoon() {
        ClaimRules.Access access = ClaimRules.access(open("other", SEEN), ME, SEEN.plus(Duration.ofSeconds(14 * 60 + 59)),
                true);

        assertThat(access).isEqualTo(new ClaimRules.TooSoon(Instant.parse("2026-11-04T11:20:00Z")));
    }

    @Test
    void openAnywayIsTooSoonOnceANewerSeenArrived() {
        Instant now = SEEN.plus(Duration.ofMinutes(30));
        assertThat(ClaimRules.access(open("other", now.minusSeconds(60)), ME, now, true))
                .isInstanceOf(ClaimRules.TooSoon.class);
    }

    // §5 claim rules: L the snapshot's generation, M the marker's, G and D the working copy's

    @Test
    void aMarkerNewerThanTheSnapshotIsNotSyncedYet() {
        assertThat(ClaimRules.claim(5, 4, 4L, false)).isEqualTo(ClaimRules.Claim.NOT_SYNCED);
        assertThat(ClaimRules.claim(5, 4, null, false)).isEqualTo(ClaimRules.Claim.NOT_SYNCED);
        assertThat(ClaimRules.claim(5, 4, 3L, true)).isEqualTo(ClaimRules.Claim.NOT_SYNCED);
    }

    @Test
    void aWorkingCopyInStepIsUsedWithOrWithoutUnsentChanges() {
        assertThat(ClaimRules.claim(4, 4, 4L, false)).isEqualTo(ClaimRules.Claim.USE_WORKING_COPY);
        assertThat(ClaimRules.claim(4, 4, 4L, true)).isEqualTo(ClaimRules.Claim.USE_WORKING_COPY);
        assertThat(ClaimRules.claim(0, 4, 4L, true)).isEqualTo(ClaimRules.Claim.USE_WORKING_COPY); // no marker
    }

    @Test
    void aWorkingCopyOutOfStepWithUnsentChangesIsDiscarded() {
        assertThat(ClaimRules.claim(6, 6, 4L, true)).isEqualTo(ClaimRules.Claim.DISCARD);
        assertThat(ClaimRules.claim(4, 6, 7L, true)).isEqualTo(ClaimRules.Claim.DISCARD);
    }

    @Test
    void otherwiseTheSnapshotIsCopiedDown() {
        assertThat(ClaimRules.claim(6, 6, null, false)).isEqualTo(ClaimRules.Claim.COPY_DOWN);
        assertThat(ClaimRules.claim(6, 6, 4L, false)).isEqualTo(ClaimRules.Claim.COPY_DOWN);
        assertThat(ClaimRules.claim(0, 1, null, false)).isEqualTo(ClaimRules.Claim.COPY_DOWN);
    }

    @Test
    void minutesAgoAreWholeMinutes() {
        assertThat(ClaimRules.minutesAgo(SEEN, SEEN.plus(Duration.ofSeconds(14 * 60 + 59)))).isEqualTo(14);
        assertThat(ClaimRules.minutesAgo(SEEN, SEEN.plus(Duration.ofMinutes(15)))).isEqualTo(15);
    }
}
