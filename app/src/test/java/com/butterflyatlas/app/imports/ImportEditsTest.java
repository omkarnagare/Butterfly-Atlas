package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.butterflyatlas.app.imports.ImportStoreTest.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportEditsTest {

    @TempDir
    Path dir;

    private Library library;
    private Database db;
    private ImportStore store;
    private ImportEdits edits;
    private long run;

    @BeforeEach
    void setUp() throws Exception {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        db = library.database();
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        edits = new ImportEdits(() -> library, TestBundles.CLOCK);
        run = store.create(Files.createDirectories(dir.resolve("Photos"))).id();
    }

    private Map<Long, ImportStore.Item> byId() {
        return store.items(run, null).items().stream().collect(Collectors.toMap(ImportStore.Item::id, Function.identity()));
    }

    private static List<Long> ids(ImportStore.ItemsPage page) {
        return page.items().stream().map(ImportStore.Item::id).toList();
    }

    @Test
    void assigningASpeciesChangesOnlyTheItemsItAppliesTo() {
        long pending = item(db, run, "pending.jpg");
        long read = item(db, run, "read.jpg", "read_state", "read", "skipped", 1);
        long same = item(db, run, "same.jpg", "read_state", "read", "taxon_id", "A5");
        long filed = item(db, run, "filed.jpg", "read_state", "read", "taxon_id", "A6", "outcome", "filed");
        long missing = item(db, run, "missing.jpg", "read_state", "missing");
        long heic = item(db, run, "a.heic", "read_state", "unsupported");
        long failed = item(db, run, "locked.jpg", "read_state", "read", "taxon_id", "A6", "outcome", "failed", "error", "x");
        long before = store.items(run, null).version();

        ImportStore.ItemsPage page = edits.assign(run, List.of(pending, read, same, filed, missing, heic, failed, 404L), "A5");

        assertThat(ids(page)).containsExactly(pending, read, failed);
        assertThat(page.version()).isEqualTo(before + 1);
        assertThat(page.taxa()).containsKey("A5");
        Map<Long, ImportStore.Item> items = byId();
        assertThat(items.get(read).skipped()).as("assigning un-skips").isFalse();
        assertThat(items.get(failed).outcome()).as("a new decision clears a failed filing").isNull();
        assertThat(items.get(failed).error()).isNull();
        assertThat(items.get(filed).taxonId()).isEqualTo("A6");
        assertThat(items.get(missing).taxonId()).isNull();
        assertThat(store.items(run, before).items()).extracting(ImportStore.Item::id).containsExactly(pending, read, failed);

        ImportStore.ItemsPage again = edits.assign(run, List.of(pending, read), "A5");
        assertThat(again.items()).as("nothing changed").isEmpty();
        assertThat(again.version()).as("and no change number was taken").isEqualTo(before + 1);

        ImportStore.ItemsPage cleared = edits.assign(run, List.of(pending), null);
        assertThat(ids(cleared)).containsExactly(pending);
        assertThat(byId().get(pending).taxonId()).isNull();
    }

    @Test
    void onlyASpeciesCanBeAssigned() {
        long id = item(db, run, "a.jpg", "read_state", "read");

        assertThatThrownBy(() -> edits.assign(run, List.of(id), "A4")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("not_a_species");
            assertThat(e.getMessage()).isEqualTo("Photos can only be filed under a species.");
        });
        assertThatThrownBy(() -> edits.assign(run, List.of(id), "A404")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> edits.assign(run, List.of(), "A5")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID));
        assertThatThrownBy(() -> edits.assign(404, List.of(id), "A5")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThat(byId().get(id).taxonId()).isNull();
    }

    @Test
    void tagsAreAddedAndRemovedAndOnlyChangedItemsAnswer() {
        long a = item(db, run, "a.jpg", "read_state", "read");
        long b = item(db, run, "b.jpg", "read_state", "read");
        long filed = item(db, run, "c.jpg", "read_state", "read", "outcome", "duplicate");
        edits.tags(run, List.of(a), List.of("male"), List.of());

        ImportStore.ItemsPage page = edits.tags(run, List.of(a, b, filed), List.of("male", "larva"), List.of());

        assertThat(ids(page)).containsExactly(a, b);
        assertThat(byId().get(a).tags()).containsExactly("larva", "male");
        assertThat(byId().get(filed).tags()).isEmpty();
        ImportStore.ItemsPage removed = edits.tags(run, List.of(a, b), List.of(), List.of("larva", "female"));
        assertThat(ids(removed)).containsExactly(a, b);
        assertThat(byId().get(b).tags()).containsExactly("male");
        assertThat(edits.tags(run, List.of(a, b), List.of("male"), List.of()).items()).isEmpty();

        assertThatThrownBy(() -> edits.tags(run, List.of(a), List.of("wings"), List.of()))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("unknown_tag"));
        assertThatThrownBy(() -> edits.tags(run, List.of(a), List.of("male"), List.of("male")))
                .isInstanceOf(AtlasException.class);
    }

    @Test
    void skippingAndTakingBack() {
        long a = item(db, run, "a.jpg", "read_state", "read", "taxon_id", "A5");
        long failed = item(db, run, "b.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "failed");
        long heic = item(db, run, "c.heic", "read_state", "unsupported");

        assertThat(ids(edits.skip(run, List.of(a, failed, heic), true))).containsExactly(a, failed);
        assertThat(byId().get(a).skipped()).isTrue();
        assertThat(byId().get(a).taxonId()).as("the species is kept").isEqualTo("A5");
        assertThat(byId().get(failed).outcome()).isNull();
        assertThat(store.counts(run).skipped()).isEqualTo(2);
        assertThat(edits.skip(run, List.of(a), true).items()).isEmpty();
        assertThat(ids(edits.skip(run, List.of(a), false))).containsExactly(a);
        assertThat(store.counts(run).assigned()).isEqualTo(1);
    }

    @Test
    void acceptingSuggestionsLeavesItemsWithoutOneAlone() {
        long suggested = item(db, run, "a.jpg", "read_state", "read", "suggested_taxon_id", "A8", "skipped", 1);
        long none = item(db, run, "b.jpg", "read_state", "read");
        long accepted = item(db, run, "c.jpg", "read_state", "read", "suggested_taxon_id", "A8", "taxon_id", "A8");

        ImportStore.ItemsPage page = edits.acceptSuggestions(run, List.of(suggested, none, accepted));

        assertThat(ids(page)).containsExactly(suggested);
        assertThat(byId().get(suggested).taxonId()).isEqualTo("A8");
        assertThat(byId().get(suggested).skipped()).isFalse();
        assertThat(byId().get(none).taxonId()).isNull();
    }

    @Test
    void finishingSkipsWhatIsStillWaiting() {
        long waiting = item(db, run, "a.jpg", "read_state", "read", "taxon_id", "A5");
        long pending = item(db, run, "b.jpg");
        long filed = item(db, run, "c.jpg", "read_state", "read", "outcome", "filed");
        long failed = item(db, run, "d.jpg", "read_state", "read", "outcome", "failed");
        long heic = item(db, run, "e.heic", "read_state", "unsupported");

        assertThat(edits.skipRest(run)).isEqualTo(2);

        Map<Long, ImportStore.Item> items = byId();
        assertThat(items.get(waiting).skipped()).isTrue();
        assertThat(items.get(pending).skipped()).isTrue();
        assertThat(items.get(filed).skipped()).isFalse();
        assertThat(items.get(failed).skipped()).isFalse();
        assertThat(items.get(heic).skipped()).isFalse();
        assertThat(edits.skipRest(run)).isZero();
    }

    @Test
    void itemsOfAnotherRunAreNeverTouched() throws Exception {
        long other = store.create(Files.createDirectories(dir.resolve("Other"))).id();
        long foreign = item(db, other, "f.jpg", "read_state", "read", "suggested_taxon_id", "A8");
        long mine = item(db, run, "m.jpg", "read_state", "read");

        assertThat(edits.assign(run, List.of(foreign), "A5").items()).isEmpty();
        assertThat(edits.tags(run, List.of(foreign), List.of("male"), List.of()).items()).isEmpty();
        assertThat(edits.skip(run, List.of(foreign), true).items()).isEmpty();
        assertThat(edits.acceptSuggestions(run, List.of(foreign, mine)).items()).isEmpty();

        ImportStore.Item item = store.items(other, null).items().get(0);
        assertThat(item.taxonId()).isNull();
        assertThat(item.skipped()).isFalse();
        assertThat(item.tags()).isEmpty();
    }

    @Test
    void clearingASpeciesIsADecisionToo() {
        long failed = item(db, run, "a.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "failed", "error", "x");

        assertThat(ids(edits.assign(run, List.of(failed), null))).containsExactly(failed);

        ImportStore.Item item = byId().get(failed);
        assertThat(item.taxonId()).isNull();
        assertThat(item.outcome()).isNull();
        assertThat(item.error()).isNull();
    }

    @Test
    void theRunIsCheckedBeforeAnythingElse() {
        assertThatThrownBy(() -> edits.assign(404, List.of(), "A4")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThatThrownBy(() -> edits.assign(404, List.of(), "A404")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThatThrownBy(() -> edits.tags(404, List.of(), List.of("wings"), List.of())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThatThrownBy(() -> edits.skip(404, List.of(), true)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThatThrownBy(() -> edits.acceptSuggestions(404, List.of())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
    }

    @Test
    void manyItemsAreEditedInChunks() {
        List<Long> all = new java.util.ArrayList<>();
        for (int n = 0; n < 1100; n++) {
            all.add(item(db, run, "p" + n + ".jpg", "read_state", "read"));
        }

        ImportStore.ItemsPage page = edits.assign(run, all, "A5");

        assertThat(page.items()).hasSize(1100);
        assertThat(store.counts(run).assigned()).isEqualTo(1100);
        assertThat(edits.tags(run, all, List.of("male"), List.of()).items()).hasSize(1100);
        assertThat(edits.skip(run, all, true).items()).hasSize(1100);
    }

    @Test
    void failedItemsKeepTheirFailureUnlessTheDecisionReplacesIt() {
        long accept = item(db, run, "a.jpg", "read_state", "read", "suggested_taxon_id", "A8", "outcome", "failed", "error", "x");
        long tag = item(db, run, "b.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "failed", "error", "x");
        long untake = item(db, run, "c.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "failed", "error", "x");

        edits.acceptSuggestions(run, List.of(accept));
        assertThat(byId().get(accept).outcome()).isNull();
        assertThat(byId().get(accept).error()).isNull();
        assertThat(byId().get(accept).taxonId()).isEqualTo("A8");

        assertThat(ids(edits.tags(run, List.of(tag), List.of("male"), List.of()))).containsExactly(tag);
        assertThat(byId().get(tag).outcome()).isEqualTo("failed");
        assertThat(byId().get(tag).error()).isEqualTo("x");

        assertThat(edits.skip(run, List.of(untake), false).items()).isEmpty();
        assertThat(byId().get(untake).outcome()).isEqualTo("failed");
    }
}
