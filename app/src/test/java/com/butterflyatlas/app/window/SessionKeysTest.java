package com.butterflyatlas.app.window;

import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-launch secret (design §3, contract §2): one-time launch keys, 60 seconds, and the cookie's one secret. */
class SessionKeysTest {

    private final TestClock clock = new TestClock();
    private final SessionKeys keys = new SessionKeys(clock);

    @Test
    void theLaunchWordsStayAsTheBrowserAndASecondLaunchUseThem() {
        assertThat(SessionKeys.LAUNCH_PATH).isEqualTo("/launch");
        assertThat(SessionKeys.LAUNCH_QUERY).isEqualTo("launch?k=");
        assertThat(SessionKeys.NEW_LAUNCH_PATH).isEqualTo("/launch/new");
        assertThat(SessionKeys.KEY_HEADER).isEqualTo("X-Atlas-Key");
    }

    @Test
    void aKeyWorksOnceAndIsUrlSafe() {
        String key = keys.newKey();

        assertThat(key).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, base64url without padding
        assertThat(keys.redeem(key)).isTrue();
        assertThat(keys.redeem(key)).isFalse();
    }

    @Test
    void aKeyExpiresAfterSixtySeconds() {
        String early = keys.newKey();
        String late = keys.newKey();

        clock.advance(Duration.ofSeconds(59));
        assertThat(keys.redeem(early)).isTrue();
        clock.advance(Duration.ofSeconds(2));
        assertThat(keys.redeem(late)).isFalse();
    }

    @Test
    void aWrongOrMissingKeyIsRefused() {
        keys.newKey();

        assertThat(keys.redeem("nope")).isFalse();
        assertThat(keys.redeem("")).isFalse();
        assertThat(keys.redeem(null)).isFalse();
    }

    @Test
    void theSecretIsTheSameForTheWholeRunAndDiffersBetweenRuns() {
        String secret = keys.secret();
        keys.newKey();
        keys.redeem("x");
        clock.advance(Duration.ofHours(1));

        assertThat(keys.secret()).isEqualTo(secret).matches("[A-Za-z0-9_-]{43}");
        assertThat(new SessionKeys(clock).secret()).isNotEqualTo(secret);
    }

    @Test
    void onlyTheSecretIsAValidCookieValue() {
        assertThat(keys.validCookie(keys.secret())).isTrue();
        assertThat(keys.validCookie(keys.newKey())).isFalse();
        assertThat(keys.validCookie(keys.secret() + "x")).isFalse();
        assertThat(keys.validCookie(null)).isFalse();
    }

    @Test
    void theInstanceKeyOfThePointerFileIsItsOwnLongLivedValue() {
        String instance = keys.instanceKey();

        assertThat(instance).isNotEqualTo(keys.secret()).matches("[A-Za-z0-9_-]{43}");
        clock.advance(Duration.ofDays(1));
        assertThat(keys.validInstanceKey(instance)).isTrue();
        assertThat(keys.validInstanceKey(keys.secret())).isFalse();
        assertThat(keys.validInstanceKey(null)).isFalse();
    }

    @Test
    void theCookieIsNamedWithThePort() {
        assertThat(SessionKeys.cookieName(7878)).isEqualTo("atlas_session_7878");
    }

    @Test
    void expiredKeysDoNotPileUp() {
        for (int i = 0; i < 100; i++) {
            keys.newKey();
        }
        clock.advance(Duration.ofMinutes(5));
        keys.newKey();

        assertThat(keys.pending()).isEqualTo(1);
    }

    @Test
    void aKeyThatIsUsedIsAnnouncedOnceAndOnlyThen() {
        java.util.List<String> used = new java.util.ArrayList<>();
        keys.onRedeemed(used::add);
        String key = keys.newKey();

        assertThat(keys.redeem("nope")).isFalse();
        assertThat(keys.redeem(key)).isTrue();
        assertThat(keys.redeem(key)).isFalse();

        assertThat(used).containsExactly(key);
    }
}
