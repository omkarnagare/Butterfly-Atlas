package com.butterflyatlas.app.window;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The per-launch secret (design §3). Launch keys open a window once: random, one-time, good for {@link #LIFE} (contract
 * §2). The process secret is the cookie's value for the whole run (a second window never invalidates the first); the
 * instance key is the pointer file's, for asking a running copy for another window. All are 32 random bytes, URL-safe.
 */
public final class SessionKeys {

    /** How long a launch key is good for, unused. */
    public static final Duration LIFE = Duration.ofSeconds(60);
    /** The launch exchange: {@code GET /launch?k=<launch key>} sets the cookie (contract §2). */
    public static final String LAUNCH_PATH = "/launch";
    /** The query parameter that carries the launch key. */
    public static final String KEY_PARAM = "k";
    /** {@link #LAUNCH_PATH} relative to the server's address, waiting for its key. */
    public static final String LAUNCH_QUERY = "launch?" + KEY_PARAM + "=";
    /** A second launch asks the running copy for a launch key here. */
    public static final String NEW_LAUNCH_PATH = "/launch/new";
    /** The header a second launch sends the pointer file's instance key in. */
    public static final String KEY_HEADER = "X-Atlas-Key";

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String secret = token();
    private final String instanceKey = token();
    /** The launch keys not yet used, with when each expires (guarded by this). */
    private final Map<String, Instant> pending = new HashMap<>();
    private final List<Consumer<String>> redeemed = new CopyOnWriteArrayList<>();

    public SessionKeys(Clock clock) {
        this.clock = clock;
    }

    /** The cookie's name: it carries the port, as cookies are not kept apart by port (audit 6). */
    public static String cookieName(int port) {
        return "atlas_session_" + port;
    }

    /** The value of the session cookie: the same for the whole run. */
    public String secret() {
        return secret;
    }

    /** The key in the pointer file, which lets a second launch ask for a window. */
    public String instanceKey() {
        return instanceKey;
    }

    /** A new launch key, good once for {@link #LIFE}. */
    public synchronized String newKey() {
        Instant now = clock.instant();
        pending.values().removeIf(expires -> !expires.isAfter(now));
        String key = token();
        pending.put(key, now.plus(LIFE));
        return key;
    }

    /** Told each launch key that is used (the redirect file that holds it is then deleted). */
    public void onRedeemed(Consumer<String> listener) {
        redeemed.add(listener);
    }

    /** Uses a launch key: true when it was known, unused and not expired. */
    public boolean redeem(String key) {
        boolean used;
        synchronized (this) {
            Instant expires = key == null ? null : pending.remove(key);
            used = expires != null && expires.isAfter(clock.instant());
        }
        if (used) {
            redeemed.forEach(listener -> listener.accept(key));
        }
        return used;
    }

    /** Whether a cookie's value is the session's secret (compared in constant time). */
    public boolean validCookie(String value) {
        return same(secret, value);
    }

    public boolean validInstanceKey(String value) {
        return same(instanceKey, value);
    }

    /** Launch keys issued and not yet used or expired (tests). */
    synchronized int pending() {
        return pending.size();
    }

    private static boolean same(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
