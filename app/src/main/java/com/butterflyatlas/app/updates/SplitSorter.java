package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.updates.UpdateViews.Ref;
import com.butterflyatlas.app.updates.UpdateViews.Split;
import com.butterflyatlas.app.updates.UpdateViews.SplitBrief;
import com.butterflyatlas.app.updates.UpdateViews.SplitTarget;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sorting a split species' photos into the species it was split into (5b contract §5, design D13): the photos still to
 * sort, and Sort: into another species they move as Move moves them (the target's next numbers, tags and places kept,
 * cover cleared, {@code photo.move} logged), in one write with undo; into the split species itself (when it keeps its
 * id) they stay and are no longer to sort. Refused while a taxonomy update runs, waits or is paused.
 */
public final class SplitSorter {

    private final Supplier<Library> library;
    private final PhotoStore photos;
    private final PhotoQueries queries;
    private final Runnable changed;

    /** @param changed told after each sort (the pending update's summary is worked out again) */
    public SplitSorter(Supplier<Library> library, PhotoStore photos, PhotoQueries queries, Runnable changed) {
        this.library = library;
        this.photos = photos;
        this.queries = queries;
        this.changed = changed;
    }

    /** The split species with live photos still to sort, by name. */
    public List<SplitBrief> splits() {
        return library.get().database().read(Splits::briefs);
    }

    /** One split species, its targets and its photos still to sort; 404 {@code not_found} when it was never split. */
    public Split split(String id) {
        record Read(SplitBrief brief, List<SplitTarget> targets, List<Ref> missing, int total, Set<Long> toSort) {
        }
        Read read = library.get().database().snapshot(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            SplitBrief brief = Splits.brief(c, snapshot, id).orElseThrow(() -> AtlasException.notFound("There is no split"
                    + " species " + id + " in your atlas."));
            List<SplitTarget> targets = new ArrayList<>();
            List<Ref> missing = new ArrayList<>();
            Splits.Logged logged = Splits.logged(c, id).orElseThrow();
            for (String target : logged.targets()) {
                TaxonSnapshot.Row row = snapshot.find(target).orElse(null);
                if (row != null && row.active()) {
                    targets.add(new SplitTarget(row.id(), row.name(), row.common(), snapshot.family(row), snapshot.count(row.id())));
                } else {
                    missing.add(row == null ? new Ref(target, target, null) : new Ref(row.id(), row.name(), row.common()));
                }
            }
            int total = Math.max(logged.photos(), brief.photos());
            return new Read(brief, targets, missing, total, new HashSet<>(Splits.toSort(c, id)));
        });
        List<Photo> left = queries.photos(id).stream().filter(p -> read.toSort().contains(p.id())).toList();
        return new Split(read.brief(), read.targets(), read.missing(), left.size(), read.total(), left);
    }

    /**
     * Sorts photos of the split species {@code id} into {@code target}, one of the species it was split into; 400
     * {@code invalid} for another target, no photos, or photos not waiting to be sorted here; 409
     * {@code taxonomy_updating} while an update runs, waits or is paused. Answers the split as it is then.
     */
    public Split sort(String id, List<Long> photoIds, String target) {
        Library lib = library.get();
        lib.gate().check();
        lib.photoHold().check();
        Split split = split(id);
        if (split.targets().stream().noneMatch(t -> t.id().equals(target))) {
            throw invalid("Choose one of the species " + split.taxon().name() + " was split into.");
        }
        List<Long> ids = photoIds == null ? List.of() : photoIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw invalid(AtlasException.NO_PHOTOS_CHOSEN);
        }
        Set<Long> waiting = new HashSet<>(lib.database().read(c -> Splits.toSort(c, id)));
        if (!waiting.containsAll(ids)) {
            throw invalid("Choose photos of " + split.taxon().name() + " that are still to sort.");
        }
        if (target.equals(id)) {
            lib.gate().whileOpen(() -> lib.database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM split_photo WHERE image_id = ? AND taxon_id = ?")) {
                    for (long photo : ids) {
                        ps.setLong(1, photo);
                        ps.setString(2, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            }));
        } else {
            photos.move(ids, target);
        }
        changed.run();
        return split(id);
    }

    /** 400 {@code invalid}. */
    private static AtlasException invalid(String message) {
        return new AtlasException(AtlasException.Kind.INVALID, ErrorCodes.INVALID, message);
    }
}
