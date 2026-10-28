package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The backup preference and the last backup, kept in the library's own database (contract §1, §3). */
class BackupSettingsTest {

    @TempDir
    Path home;

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");

    @Test
    void aLibraryNeverAskedIsRemindMeAndNotAsked() {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));

        BackupSettings.Values values = new BackupSettings(library::database).read();

        assertThat(values.asked()).isFalse();
        assertThat(values.preference()).isEqualTo("remind");
        assertThat(values.lastAt()).isNull();
        assertThat(values.lastWhat()).isNull();
        assertThat(values.lastUnread()).isZero();
    }

    @Test
    void settingThePreferenceMarksItAskedAndItIsKeptPerLibrary() {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        Library other = TestBundles.fixtureLibrary(home.resolve("Other"));
        BackupSettings settings = new BackupSettings(library::database);

        settings.setPreference("less");

        assertThat(settings.read().asked()).isTrue();
        assertThat(settings.read().preference()).isEqualTo("less");
        assertThat(new BackupSettings(other::database).read().asked()).isFalse();
        settings.setPreference("none");
        assertThat(settings.read().preference()).isEqualTo("none");
    }

    @Test
    void anyOtherPreferenceIs400BadPreferenceAndChangesNothing() {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        BackupSettings settings = new BackupSettings(library::database);
        settings.setPreference("less");

        for (String bad : new String[] {"weekly", "", null, "REMIND"}) {
            assertThatThrownBy(() -> settings.setPreference(bad)).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
                assertThat(e.code()).isEqualTo("bad_preference");
            });
        }
        assertThat(settings.read().preference()).isEqualTo("less");
    }

    @Test
    void theLastBackupIsRecordedAsTheThreeKeysAndReadBack() {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        BackupSettings settings = new BackupSettings(library::database);

        library.database().write(c -> {
            BackupSettings.recordLast(c, "2026-10-09T08:00:00Z", "everything", 4);
            return null;
        });

        BackupSettings.Values values = settings.read();
        assertThat(values.lastAt()).isEqualTo(Instant.parse("2026-10-09T08:00:00Z"));
        assertThat(values.lastWhat()).isEqualTo("everything");
        assertThat(values.lastUnread()).isEqualTo(4);
        assertThat(values.asked()).as("a backup is not an answer to the question").isFalse();
    }

    @Test
    void dueFollowsThePreferenceAndTheDaysSinceTheLastBackup() {
        Instant eightDays = NOW.minusSeconds(8 * 86_400L);
        Instant sixDays = NOW.minusSeconds(6 * 86_400L + 3_600);
        Instant thirtyOne = NOW.minusSeconds(31 * 86_400L);
        Instant twentyNine = NOW.minusSeconds(29 * 86_400L);

        assertThat(BackupSettings.due("remind", null, NOW)).isTrue();
        assertThat(BackupSettings.due("remind", eightDays, NOW)).isTrue();
        assertThat(BackupSettings.due("remind", NOW.minusSeconds(7 * 86_400L), NOW)).isTrue();
        assertThat(BackupSettings.due("remind", sixDays, NOW)).isFalse();
        assertThat(BackupSettings.due("less", null, NOW)).isTrue();
        assertThat(BackupSettings.due("less", twentyNine, NOW)).isFalse();
        assertThat(BackupSettings.due("less", thirtyOne, NOW)).isTrue();
        assertThat(BackupSettings.due("none", null, NOW)).isFalse();
        assertThat(BackupSettings.due("none", thirtyOne, NOW)).isFalse();
    }

    @Test
    void daysSinceIsWholeDaysAndNullWhenNeverBackedUp() {
        assertThat(BackupSettings.daysSince(null, NOW)).isNull();
        assertThat(BackupSettings.daysSince(NOW.minusSeconds(12 * 86_400L + 80_000), NOW)).isEqualTo(12);
        assertThat(BackupSettings.daysSince(NOW.plusSeconds(3600), NOW)).isZero(); // a clock set back: never negative
    }

    @Test
    void aDamagedLastBackupDateOrCountIsNeverBackedUpAndZeroNotAnError() {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        BackupSettings settings = new BackupSettings(library::database);
        library.database().write(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('backup.last_at', 'last Tuesday'),"
                        + " ('backup.last_what', 'everything'), ('backup.last_unread', 'a few')");
            }
            return null;
        });

        BackupSettings.Values values = settings.read();

        assertThat(values.lastAt()).isNull();
        assertThat(values.lastUnread()).isZero();
        assertThat(values.lastWhat()).isEqualTo("everything");
        assertThat(BackupSettings.due(values.preference(), values.lastAt(), NOW)).as("never backed up: Home reminds").isTrue();
        assertThat(new Backups(() -> library, java.time.Clock.fixed(NOW,
                java.time.ZoneOffset.UTC)).list().lastAt()).isNull();
    }
}
