package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.TagService.Tag;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagServiceTest {

    @TempDir
    Path dir;

    private Library library;
    private TagService tags;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        tags = new TagService(() -> library);
    }

    private long photo(String taxonId) {
        return TestBundles.addImage(library.database(), taxonId);
    }

    private Tag tag(String key) {
        return tags.list().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void listsTheBuiltInTagsInOrder() {
        assertThat(tags.list()).extracting(Tag::key).containsExactly(
                "egg", "larva", "pupa", "upper_wing", "under_wing", "male", "female", "dsf", "wsf", "unconfirmed");
        assertThat(tags.list()).extracting(Tag::label).containsExactly(
                "egg", "larva", "pupa", "upper_wing", "under_wing", "male", "female", "DSF", "WSF", "unconfirmed");
        assertThat(tags.list()).allSatisfy(t -> {
            assertThat(t.source()).isEqualTo("builtin");
            assertThat(t.photoCount()).isZero();
        });
    }

    @Test
    void countsOnlyLivePhotos() {
        long a = photo("A5");
        long b = photo("A6");
        tags.setPhotoTags(a, List.of("larva"));
        tags.setPhotoTags(b, List.of("larva", "male"));
        TestBundles.markDeleted(library.database(), b);

        assertThat(tag("larva").photoCount()).isEqualTo(1);
        assertThat(tag("male").photoCount()).isZero();
    }

    @Test
    void createsUserTagsWithTheNameInItsFormAndAKeyMadeFromIt() {
        Tag created = tags.create("  Mud-puddling  ");
        tags.create("basking");

        assertThat(created.key()).isEqualTo("mud_puddling");
        assertThat(created.label()).isEqualTo("mud_puddling");
        assertThat(created.source()).isEqualTo("user");
        assertThat(tags.list()).extracting(Tag::key).endsWith("wsf", "unconfirmed", "basking", "mud_puddling");
    }

    @Test
    void keysStayUniqueAndLabelsMustBeNew() {
        Tag mud = tags.create("Mud-puddling");
        tags.rename(mud.id(), "puddling");

        assertThat(tags.create("mud puddling").key()).isEqualTo("mud_puddling-2");
        assertThatThrownBy(() -> tags.create("PUDDLING")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.getMessage()).isEqualTo("There is already a tag called \"puddling\".");
        });
        assertThatThrownBy(() -> tags.create("Puddling!")).isInstanceOf(AtlasException.class);
        assertThatThrownBy(() -> tags.create("Male")).isInstanceOf(AtlasException.class);
    }

    @Test
    void labelsAreTidiedAndChecked() {
        assertThat(tags.create("  Basking \t in   sun ").label()).isEqualTo("basking_in_sun");
        assertThat(tags.create("UV light").label()).isEqualTo("UV_light");
        for (String empty : List.of("   ", "!!!")) {
            assertThatThrownBy(() -> tags.create(empty)).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.getMessage()).isEqualTo("Type a name for the tag."));
        }
        assertThatThrownBy(() -> tags.create("mud puddling wet sand")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Keep tag names to 3 words or fewer."));
        assertThatThrownBy(() -> tags.create("x".repeat(41))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Keep tag names to 40 characters or fewer."));
    }

    @Test
    void renamingChangesTheLabelButNeverTheKey() {
        Tag dsf = tag("dsf");

        Tag renamed = tags.rename(dsf.id(), "dry season form");

        assertThat(renamed.key()).isEqualTo("dsf");
        assertThat(renamed.label()).isEqualTo("dry_season_form");
        assertThat(renamed.source()).isEqualTo("builtin");
        assertThat(tags.rename(dsf.id(), "Dry-Season Form").label()).isEqualTo("dry_season_form");
        assertThatThrownBy(() -> tags.rename(dsf.id(), "dry season form two")).isInstanceOf(AtlasException.class);
        assertThatThrownBy(() -> tags.rename(dsf.id(), "WSF")).isInstanceOf(AtlasException.class);
    }

    @Test
    void deletingAUserTagTakesItOffItsPhotos() {
        Tag basking = tags.create("basking");
        long a = photo("A5");
        long b = photo("A5");
        tags.setPhotoTags(a, List.of("basking", "male"));
        tags.setPhotoTags(b, List.of("basking"));

        assertThat(tags.delete(basking.id())).isEqualTo(2);

        assertThat(tags.list()).extracting(Tag::key).doesNotContain("basking");
        assertThat(tags.setPhotoTags(a, List.of("male")).tags()).containsExactly("male");
    }

    @Test
    void builtInTagsCannotBeDeleted() {
        assertThatThrownBy(() -> tags.delete(tag("egg").id())).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.getMessage()).isEqualTo("Built-in tags can be renamed but not deleted.");
        });
        assertThatThrownBy(() -> tags.delete(999)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> tags.rename(999, "x")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void setPhotoTagsReplacesThemAll() {
        long a = photo("A5");

        assertThat(tags.setPhotoTags(a, List.of("male", "larva")).tags()).containsExactly("larva", "male");
        assertThat(tags.setPhotoTags(a, List.of()).tags()).isEmpty();
        assertThatThrownBy(() -> tags.setPhotoTags(a, List.of("wingspan"))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("There is no tag called \"wingspan\"."));
        TestBundles.markDeleted(library.database(), a);
        assertThatThrownBy(() -> tags.setPhotoTags(a, List.of("male"))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void bulkEditAddsAndRemoves() {
        long a = photo("A5");
        long b = photo("A5");
        tags.setPhotoTags(a, List.of("female", "pupa"));

        List<Photo> edited = tags.edit(List.of(b, a), List.of("male"), List.of("female"));

        assertThat(edited).extracting(Photo::id).containsExactly(b, a);
        assertThat(edited.get(0).tags()).containsExactly("male");
        assertThat(edited.get(1).tags()).containsExactly("pupa", "male");
    }

    @Test
    void aBulkEditOfManyPhotosKeepsTheirOrderAndTags() {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            ids.add(photo(i % 2 == 0 ? "A5" : "A6"));
        }
        tags.edit(ids.subList(0, 600), List.of("female", "larva"), List.of());
        List<Long> backwards = new java.util.ArrayList<>(ids);
        java.util.Collections.reverse(backwards);

        List<Photo> edited = tags.edit(backwards, List.of("male", "upper_wing"), List.of("female"));

        assertThat(edited).extracting(Photo::id).containsExactlyElementsOf(backwards);
        assertThat(edited.get(0).tags()).containsExactly("upper_wing", "male");
        assertThat(edited.get(1199).tags()).containsExactly("larva", "upper_wing", "male");
        assertThat(tag("male").photoCount()).isEqualTo(1200);
        assertThat(tag("female").photoCount()).isZero();
        assertThat(tag("larva").photoCount()).isEqualTo(600);
        TestBundles.markDeleted(library.database(), ids.get(700));
        assertThatThrownBy(() -> tags.edit(ids, List.of("egg"), List.of())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("There is no photo " + ids.get(700) + " in your atlas."));
        assertThat(tag("egg").photoCount()).isZero();
    }

    @Test
    void bulkEditChecksEverythingBeforeChangingAnything() {
        long a = photo("A5");

        assertThatThrownBy(() -> tags.edit(List.of(), List.of("male"), List.of())).isInstanceOfSatisfying(
                AtlasException.class, e -> assertThat(e.getMessage()).isEqualTo("Choose at least one photo."));
        assertThatThrownBy(() -> tags.edit(List.of(a), List.of("male"), List.of("male"))).isInstanceOf(AtlasException.class);
        assertThatThrownBy(() -> tags.edit(List.of(a, 999L), List.of("male"), List.of())).isInstanceOfSatisfying(
                AtlasException.class, e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThat(tags.edit(List.of(a), null, null).get(0).tags()).isEmpty();
    }

    @Test
    void bulkEditWithAnUnknownKeyChangesNothing() {
        long a = photo("A5");
        long b = photo("A5");
        tags.setPhotoTags(a, List.of("female"));

        assertThatThrownBy(() -> tags.edit(List.of(a, b), List.of("male", "wingspan"), List.of("female")))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("There is no tag called \"wingspan\"."));
        assertThatThrownBy(() -> tags.edit(List.of(a, b), List.of("male"), List.of("wingspan")))
                .isInstanceOf(AtlasException.class);

        assertThat(tags.edit(List.of(a, b), null, null)).extracting(Photo::tags)
                .containsExactly(List.of("female"), List.of());
    }

    @Test
    void renamingToAnotherTagsLabelIsRefusedButACaseOnlyRenameWorks() {
        Tag basking = tags.create("basking");
        Tag mud = tags.create("mud puddling");

        assertThatThrownBy(() -> tags.rename(mud.id(), "BASKING")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID));
        assertThat(tags.rename(basking.id(), "Basking").label()).isEqualTo("basking");
        assertThat(tags.rename(basking.id(), "SUN").label()).isEqualTo("SUN");
        assertThat(tags.rename(basking.id(), "sun").label()).isEqualTo("sun");
        assertThat(tags.rename(mud.id(), "Mud Puddling").key()).isEqualTo("mud_puddling");
    }

    @Test
    void labelsAreNormalisedSoLookAlikesClash() {
        Tag created = tags.create("Caf\u0065\u0301");

        assertThat(created.label()).isEqualTo("caf\u00e9");
        assertThatThrownBy(() -> tags.create("CAF\u00c9")).isInstanceOf(AtlasException.class);
    }

    private void sql(String statement) {
        library.database().write(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate(statement);
            }
            return null;
        });
    }

    @Test
    void aLibraryWithNothingToSayHasAnEmptyNote() {
        assertThat(tags.note()).isEqualTo(new TagService.Note(List.of(), List.of(), List.of(), false));
        assertThat(tags.dismissNote()).isEqualTo(new TagService.Note(List.of(), List.of(), List.of(), false));
    }

    @Test
    void theNoteSaysWhatV15MergedAndNumberedAndWhichTagsHaveMoreThan3Words() {
        sql("INSERT INTO tag_name_note (id, payload_json) VALUES (1, '{\"merged\":[{\"from\":\"mud-puddling\","
                + "\"into\":\"mud_puddling\",\"photos\":2}],\"numbered\":[{\"from\":\"Under-wing\",\"to\":\"under_wing_2\"}]}')");
        sql("INSERT INTO tag (key, label, source, sort_order) VALUES ('b', 'mud_puddling_on_wet_sand', 'user', 1000),"
                + " ('a', 'a_b_c_d', 'user', 1000), ('c', 'a_b_c', 'user', 1000)");

        TagService.Note note = tags.note();

        assertThat(note.merged()).containsExactly(new TagService.Merged("mud-puddling", "mud_puddling", 2));
        assertThat(note.numbered()).containsExactly(new TagService.Numbered("Under-wing", "under_wing_2"));
        assertThat(note.longNames()).containsExactly("a_b_c_d", "mud_puddling_on_wet_sand");
        assertThat(note.dismissed()).isFalse();

        TagService.Note dismissed = tags.dismissNote();

        assertThat(dismissed.dismissed()).isTrue();
        assertThat(dismissed.merged()).hasSize(1);
        assertThat(tags.note().dismissed()).isTrue();
        Tag longOne = tags.list().stream().filter(t -> t.key().equals("a")).findFirst().orElseThrow();
        tags.rename(longOne.id(), "a b");
        assertThat(tags.note().longNames()).containsExactly("mud_puddling_on_wet_sand");
    }
}
