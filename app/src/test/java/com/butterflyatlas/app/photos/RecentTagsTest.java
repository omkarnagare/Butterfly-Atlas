package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tag pickers' Recent row (subspecies names and import ticks design, addendum §6): the user's own tags, the most
 * recently used first, at most {@value TagService#RECENT_LIMIT}. Used = made, or added to photos (one, several, Add photos)
 * or import items; removing doesn't count, and built-in tags are never listed.
 */
class RecentTagsTest {

    @TempDir
    Path dir;

    private Library library;
    private TagService tags;
    private final Ticking clock = new Ticking();

    /** A clock that moves on a minute each time it is read. */
    static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-11-13T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            now = now.plusSeconds(60);
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        tags = new TagService(() -> library, clock);
    }

    private long photo() {
        return TestBundles.addImage(library.database(), "A5");
    }

    private String lastUsed(String key) {
        return library.database().read(c -> {
            try (var ps = c.prepareStatement("SELECT last_used_at FROM tag WHERE key = ?")) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void madeTagsAreRecentNewestFirst() {
        tags.create("roosting");
        tags.create("on dung");
        assertThat(tags.recent()).containsExactly("on_dung", "roosting");
        assertThat(lastUsed("roosting")).isEqualTo("2026-11-13T10:01:00.000000Z");
    }

    @Test
    void addingATagToPhotosMovesItToTheFrontButRemovingDoesNot() {
        long a = photo();
        long b = photo();
        tags.create("roosting");
        tags.create("on dung");
        tags.edit(List.of(a, b), List.of("roosting", "male"), List.of());
        assertThat(tags.recent()).containsExactly("roosting", "on_dung");

        tags.setPhotoTags(a, List.of("on_dung"));
        assertThat(tags.recent()).containsExactly("on_dung", "roosting");

        tags.edit(List.of(b), List.of(), List.of("roosting"));
        assertThat(tags.recent()).containsExactly("on_dung", "roosting");
        assertThat(lastUsed("male")).as("built-ins aren't tracked").isNull();
    }

    @Test
    void atMostEightAreListedAndADeletedTagDropsOutWhileARenamedOneStays() {
        for (int n = 1; n <= 10; n++) {
            tags.create("tag " + n);
        }
        assertThat(tags.recent()).hasSize(TagService.RECENT_LIMIT).first().isEqualTo("tag_10");
        assertThat(tags.recent()).last().isEqualTo("tag_3");

        long ten = tags.list().stream().filter(t -> t.key().equals("tag_10")).findFirst().orElseThrow().id();
        long nine = tags.list().stream().filter(t -> t.key().equals("tag_9")).findFirst().orElseThrow().id();
        tags.delete(ten);
        tags.rename(nine, "ninth");
        assertThat(tags.recent()).hasSize(8).startsWith("tag_9", "tag_8").endsWith("tag_2");
    }

    @Test
    void usedMarksOnlyUserTagsOfTheKeysGiven() {
        tags.create("roosting");
        tags.create("on dung");
        library.database().write(c -> {
            TagService.used(c, List.of("roosting", "male", "no_such_tag"), Instant.parse("2027-01-01T00:00:00Z"));
            return null;
        });
        assertThat(lastUsed("roosting")).isEqualTo("2027-01-01T00:00:00.000000Z");
        assertThat(lastUsed("male")).isNull();
        assertThat(tags.recent()).containsExactly("roosting", "on_dung");
    }
}
