package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.window.BrowserFinder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * For the real-window checks (JUnit tag {@value #TAG}, run with the Maven profile {@code window-checks}; contract §5):
 * they need a browser. Without one a check is skipped on a developer's computer, but fails on CI (the environment
 * variable {@code CI} is {@code true}), so CI never passes by skipping.
 */
public final class WindowChecks {

    /** The JUnit tag of the checks that open real browser windows; the default build leaves them out. */
    public static final String TAG = "window";

    private WindowChecks() {
    }

    /** The browser the app would use on this computer ({@link BrowserFinder#system()}). */
    public static Path browser() {
        return browser(BrowserFinder.system().find().browser(), System.getenv());
    }

    static Path browser(Optional<Path> found, Map<String, String> env) {
        if (found.isPresent()) {
            return found.get();
        }
        if ("true".equalsIgnoreCase(env.get("CI"))) {
            return Assertions.fail("No Edge or Chrome was found, and CI is true: a real-window check must run on CI.");
        }
        Assumptions.abort("No Edge or Chrome was found on this computer; the real-window check is skipped.");
        return null;
    }
}
