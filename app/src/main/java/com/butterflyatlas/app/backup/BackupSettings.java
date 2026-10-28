package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Supplier;

/**
 * The backup preference and the last backup, in the library's own {@code library_setting} table (contract §1): per
 * library, nothing in the config folder. Nothing here copies anything; the preference only decides whether Home reminds.
 */
final class BackupSettings {

    private static final Logger LOG = LoggerFactory.getLogger(BackupSettings.class);

    static final String REMIND = "remind";
    static final String LESS = "less";
    static final String NONE = "none";
    private static final List<String> PREFERENCES = List.of(REMIND, LESS, NONE);

    private static final String PREFERENCE_KEY = "backup.reminder";
    private static final String LAST_AT = "backup.last_at";
    private static final String LAST_WHAT = "backup.last_what";
    private static final String LAST_UNREAD = "backup.last_unread";

    /** What the library holds; {@code asked} is false until the preference was ever set. */
    record Values(boolean asked, String preference, Instant lastAt, String lastWhat, int lastUnread) {
    }

    private final Supplier<Database> database;

    BackupSettings(Supplier<Database> database) {
        this.database = database;
    }

    Values read() {
        return database.get().read(c -> {
            String preference = get(c, PREFERENCE_KEY);
            String at = get(c, LAST_AT);
            String unread = get(c, LAST_UNREAD);
            return new Values(preference != null, preference == null ? REMIND : preference, instant(at), get(c, LAST_WHAT),
                    count(unread));
        });
    }

    /** A damaged date is "never backed up", not an error on every Home and Settings screen. */
    private static Instant instant(String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (DateTimeParseException e) {
            LOG.warn("The last backup's date {} cannot be read; treated as never backed up", text);
            return null;
        }
    }

    private static int count(String text) {
        try {
            return text == null ? 0 : Math.max(0, Integer.parseInt(text.strip()));
        } catch (NumberFormatException e) {
            LOG.warn("The last backup's count {} cannot be read; treated as 0", text);
            return 0;
        }
    }

    /** 400 {@code bad_preference} for any other value. */
    void setPreference(String preference) {
        if (preference == null || !PREFERENCES.contains(preference)) {
            throw new AtlasException(AtlasException.Kind.INVALID, "bad_preference",
                    "The reminder choice must be Remind me, Remind me less often or No backup needed.");
        }
        database.get().write(c -> {
            put(c, PREFERENCE_KEY, preference);
            return null;
        });
    }

    /** Written by the write that finishes a backup, and by nothing else (contract §4). */
    static void recordLast(Connection c, String at, String what, int unread) throws SQLException {
        put(c, LAST_AT, at);
        put(c, LAST_WHAT, what);
        put(c, LAST_UNREAD, Integer.toString(unread));
    }

    /** Whether Home's line shows: remind from 7 days after the last backup, less from 30, none never; never backed up: yes. */
    static boolean due(String preference, Instant lastAt, Instant now) {
        if (NONE.equals(preference)) {
            return false;
        }
        Integer days = daysSince(lastAt, now);
        return days == null || days >= (LESS.equals(preference) ? 30 : 7);
    }

    /** Whole days since the last backup; null when there was none; never negative. */
    static Integer daysSince(Instant lastAt, Instant now) {
        return lastAt == null ? null : (int) Math.max(0, Duration.between(lastAt, now).toDays());
    }

    private static String get(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM library_setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void put(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_setting (key, value) VALUES (?, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
