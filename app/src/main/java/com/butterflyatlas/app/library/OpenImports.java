package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;

import com.butterflyatlas.app.ErrorCodes;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/**
 * Each import's revision (multi-window contract §5): a number, kept in memory from 0 when the app starts, that goes up
 * with every change to the import's items or choices, made by the import's own edits or by deleting a tag, place or
 * species one of its items chose. File is refused when the window's revision is not this one ({@code import_changed}),
 * so a window never files what it did not see. And which imports are filing: a tag, place or species one of their items
 * chose is not deleted meanwhile (contract §7). One per library, so the packages that delete tags, places and species
 * can ask it without knowing the imports.
 *
 * <p>A delete's transaction marks the imports it changes ({@link Change}) and their revisions go up once it has
 * committed; while marked, File refuses as if they had changed already, so a File that the delete's write lock held back
 * never files with a revision taken before the delete.
 */
public final class OpenImports {

    public static final String TAG_FILING = "An import is filing photos with this tag. Wait for filing to finish, then"
            + " delete it.";
    public static final String PLACE_FILING = "An import is filing photos with this place. Wait for filing to finish, then"
            + " delete it.";
    public static final String SPECIES_FILING = "An import is filing photos into this species. Wait for filing to finish,"
            + " then delete it.";

    private final Map<Long, Long> revisions = new ConcurrentHashMap<>();
    /** The imports a delete under way is changing, with how many such deletes. */
    private final Map<Long, Integer> changing = new ConcurrentHashMap<>();
    /** Whether an import is filing or about to (told by the imports when the library opens). */
    private volatile LongPredicate filing = importId -> false;
    /** Runs inside a delete's transaction once it has marked the imports it changes (tests hold a delete there). */
    public volatile Runnable afterMarked = () -> { };

    /** What tells whether an import is filing. */
    public void filing(LongPredicate filing) {
        this.filing = filing;
    }

    /** 409 {@code import_filing} in these words when one of these imports is filing. */
    public void refuseWhileFiling(Collection<Long> importIds, String message) {
        if (importIds.stream().anyMatch(filing::test)) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.IMPORT_FILING, message);
        }
    }

    /** The import's revision now. */
    public long revision(long importId) {
        return revisions.getOrDefault(importId, 0L);
    }

    /** The import changed: its revision goes up by one, and is returned. */
    public long changed(long importId) {
        return revisions.merge(importId, 1L, Long::sum);
    }

    /** Whether a delete under way is changing the import (File then refuses). */
    public boolean changing(long importId) {
        return changing.containsKey(importId);
    }

    /** A delete that may change imports: opened before its transaction, closed after it whether or not it committed. */
    public Change change() {
        return new Change();
    }

    /** The imports one delete changes. */
    public final class Change implements AutoCloseable {

        private final Set<Long> marked = new LinkedHashSet<>();
        private boolean committed;

        /** Inside the delete's transaction: these imports are being changed. */
        public void add(Collection<Long> importIds) {
            for (long id : importIds) {
                if (marked.add(id)) {
                    changing.merge(id, 1, Integer::sum);
                }
            }
            afterMarked.run();
        }

        /** The delete's transaction committed: the revisions go up when this closes. */
        public void committed() {
            committed = true;
        }

        @Override
        public void close() {
            for (long id : marked) {
                if (committed) {
                    OpenImports.this.changed(id);
                }
                changing.computeIfPresent(id, (k, n) -> n == 1 ? null : n - 1);
            }
            marked.clear();
        }
    }
}
