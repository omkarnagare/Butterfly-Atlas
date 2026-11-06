package com.butterflyatlas.app.desktop;

import com.butterflyatlas.app.window.AtlasLog;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The log for the desktop pieces (spec §3.4): a failure is written once per kind, not once per click or per poll, so a
 * native call that fails every 100 ms does not fill the log. Nothing here throws.
 */
public final class OnceLog {

    private final AtlasLog log;
    private final Set<String> told = ConcurrentHashMap.newKeySet();

    public OnceLog(AtlasLog log) {
        this.log = log;
    }

    /** Writes {@code message} the first time {@code kind} is told; true when it was written now. */
    public boolean warn(String kind, String message, Throwable cause) {
        if (!told.add(kind)) {
            return false;
        }
        try {
            log.warn(message, cause);
        } catch (RuntimeException e) {
            // a log that cannot be written is not a reason to stop
        }
        return true;
    }

    /** As {@link #warn}, at the information level. */
    public boolean info(String kind, String message) {
        if (!told.add(kind)) {
            return false;
        }
        try {
            log.info(message);
        } catch (RuntimeException e) {
            // as above
        }
        return true;
    }
}
