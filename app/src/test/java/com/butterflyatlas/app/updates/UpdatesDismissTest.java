package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Home's "The taxonomy was updated" card: its Dismiss is stored in the library (the change log) and said by
 * {@code /api/status}, so a page reload keeps it dismissed; a later finish shows the card again (polish after 5c).
 */
class UpdatesDismissTest {

    @TempDir
    Path dir;

    private UpdateFixture f;
    private Updates updates;

    /** A rename, and Neptis sankara added (the custom U1 then matches it). */
    private static Bundle v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").rename("A6", "Danaus melanippus")
                .add("A20", "A3", "genus", "Neptis", null).add("A21", "A20", "species", "Neptis sankara", null).build();
    }

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
        updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2()), f.clock,
                SpeciesFiles::moveFile, path -> Long.MAX_VALUE);
        updates.startup();
    }

    private UpdateViews.Status status() {
        return updates.status().orElseThrow();
    }

    @Test
    void aDismissedDoneUpdateStaysDismissedAndIsRecordedInTheLibrary() throws Exception {
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(status().state()).isEqualTo("done");
        assertThat(status().dismissed()).isFalse();

        updates.dismiss();

        assertThat(status().dismissed()).isTrue();
        long id = Long.parseLong(f.rows("SELECT id FROM taxonomy_update WHERE state = 'done'").getFirst());
        assertThat(f.rows("SELECT json_extract(payload_json, '$.update') FROM change_log WHERE kind = 'taxonomy.dismissed'"))
                .containsExactly(String.valueOf(id));
    }

    @Test
    void aLaterFinishIsNotDismissed() throws Exception {
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        updates.dismiss();

        updates.merge("U1", "A21");
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(status().kind()).isEqualTo("match");
        assertThat(status().dismissed()).isFalse();
    }

    @Test
    void dismissWithNothingDoneRecordsNothing() {
        updates.dismiss();

        assertThat(f.rows("SELECT COUNT(*) FROM change_log WHERE kind = 'taxonomy.dismissed'")).containsExactly("0");
        assertThat(status().state()).isEqualTo("pending");
        assertThat(status().dismissed()).isFalse();
    }
}
