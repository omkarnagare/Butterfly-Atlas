package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.FileNames;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.taxonomy.BundleLoader;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;
import com.butterflyatlas.app.taxonomy.bundle.Change;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * What one bundle does to the library (5b contract §3 "The new tree"), worked out in memory: the bundle is the truth for
 * every official taxon in it (active, parent, rank, names), and its change list says what happened to the ids that are
 * gone (merged, split). From the new tree follow the photo moves ({@link #moves}), the new names of photos in Recently
 * deleted ({@link #deleted}) and the photos still to sort after a split ({@link #splitPhotos}). The same computation
 * serves the summary's dry run, the plan and the switch-over.
 */
final class Step {

    static final String RENAME = "rename";
    static final String MOVE = "move";
    static final String MERGE = "merge";
    static final String SPLIT = "split";
    static final String ADD = "add";
    static final String REMOVE = "remove";
    /** A taxon the library has inactive (a removed species) back in the list; shown as an add. */
    static final String REACTIVATE = "reactivate";

    /**
     * One change to the library's taxa: rename and move {@code [id] → [id]}, merge {@code sources → [target]}, split
     * {@code [source] → targets}, add and reactivate {@code [] → [id]}, remove {@code [id] → []}.
     */
    record TaxonChange(String kind, String rank, List<String> from, List<String> to) {
    }

    /**
     * A live photo's file moving: to {@code newRel}, named {@code newFileName}, numbered {@code newNumber} (null: as it
     * was).
     */
    record Moved(long imageId, String taxonId, String oldRel, String newRel, String newFileName, Integer newNumber) {
    }

    /** A photo in Recently deleted given a new species, restore name and number. */
    record Renamed(long imageId, String taxonId, String fileName, Integer number) {
    }

    final Bundle bundle;
    final Tree before;
    final Tree after;
    /** Merge sources the library has (not in the bundle any more) and their target. */
    final Map<String, String> merged = new LinkedHashMap<>();
    /** Split sources the library has and their targets (the source among them when it keeps its id). */
    final Map<String, List<String>> splits = new LinkedHashMap<>();
    /** Ids new to the library, parents first. */
    final Set<String> inserted = new LinkedHashSet<>();
    final List<TaxonChange> changes = new ArrayList<>();

    private Step(Bundle bundle, Tree before) {
        this.bundle = bundle;
        this.before = before;
        this.after = before.copy();
    }

    /** The new tree and the changes of {@code bundle} over the library's {@code before} (which is not changed). */
    static Step of(Tree before, Bundle bundle) {
        Step step = new Step(bundle, before);
        step.apply();
        return step;
    }

    private void apply() {
        String groupId = BundleLoader.groupId(bundle.group().key());
        Map<String, BundleTaxon> listed = new LinkedHashMap<>();
        for (BundleTaxon t : topDown()) {
            listed.put(t.id(), t);
        }
        // A merge into, or a split towards, a taxon of another rank is not followed: photos stay on species. Such a merge
        // source is removed (inactive, its photos where they are).
        for (Change change : bundle.changes()) {
            if (change.type() == Change.Type.MERGE && change.to().size() == 1 && listed.containsKey(change.to().getFirst())) {
                BundleTaxon target = listed.get(change.to().getFirst());
                for (String source : change.from()) {
                    if (before.has(source) && !listed.containsKey(source) && before.get(source).rank.equals(target.rank())) {
                        merged.put(source, target.id());
                    }
                }
            } else if (change.type() == Change.Type.SPLIT && change.from().size() == 1 && before.has(change.from().getFirst())) {
                String rank = before.get(change.from().getFirst()).rank;
                splits.put(change.from().getFirst(), change.to().stream()
                        .filter(id -> listed.containsKey(id) && listed.get(id).rank().equals(rank)).toList());
            }
        }

        // Every official taxon in the bundle: as the bundle has it (a new one added, parents first).
        for (BundleTaxon t : listed.values()) {
            String parent = t.parent() == null ? groupId : t.parent();
            if (!after.has(parent)) {
                throw new IllegalStateException("The taxonomy file is inconsistent (taxon " + t.id() + " has unknown parent "
                        + t.parent() + ")");
            }
            Tree.Node n = after.get(t.id());
            if (n == null) {
                after.put(new Tree.Node(t.id(), parent, t.rank(), t.name(), t.common(), "official", null, t.inatId(), true,
                        null, 1));
                inserted.add(t.id());
            } else {
                n.parentId = parent;
                n.rank = t.rank();
                n.name = t.name();
                n.common = t.common();
                n.inatId = t.inatId();
                n.active = true;
            }
        }
        // An official taxon the bundle no longer has is inactive (removed, merged, split); it stays where it is.
        for (Tree.Node n : after.all()) {
            if (n.official() && !listed.containsKey(n.id)) {
                n.active = false;
            }
        }
        // A merged higher rank: what only the library still has under it (inactive species with photos) follows the target.
        Map<String, List<Tree.Node>> children = after.children();
        List<String> reparented = new ArrayList<>();
        for (Map.Entry<String, String> merge : merged.entrySet()) {
            for (Tree.Node child : children.getOrDefault(merge.getKey(), List.of())) {
                if (!listed.containsKey(child.id)) {
                    child.parentId = merge.getValue();
                    reparented.add(child.id);
                }
            }
        }
        for (Tree.Node n : after.all()) {
            if (n.familyId != null && merged.containsKey(n.familyId)) {
                n.familyId = merged.get(n.familyId); // a custom species follows its merged family
            }
        }
        for (Map.Entry<String, String> merge : merged.entrySet()) {
            Tree.Node source = after.get(merge.getKey());
            Tree.Node target = after.get(merge.getValue());
            if (source.cover != null && target.cover == null) {
                target.cover = source.cover; // the source's photos are the target's now
            }
            source.cover = null;
        }

        for (BundleTaxon t : listed.values()) {
            Tree.Node was = before.get(t.id());
            if (was == null) {
                continue;
            }
            if (!was.name.equals(t.name())) {
                changes.add(new TaxonChange(RENAME, t.rank(), List.of(t.id()), List.of(t.id())));
            }
            if (!Objects.equals(was.parentId, after.get(t.id()).parentId)) {
                changes.add(new TaxonChange(MOVE, t.rank(), List.of(t.id()), List.of(t.id())));
            }
            if (!was.active) {
                changes.add(new TaxonChange(REACTIVATE, t.rank(), List.of(), List.of(t.id())));
            }
        }
        for (String id : reparented) {
            changes.add(new TaxonChange(MOVE, after.get(id).rank, List.of(id), List.of(id)));
        }
        Set<String> targets = new HashSet<>(merged.values());
        splits.values().forEach(targets::addAll);
        for (String id : inserted) {
            if (!targets.contains(id)) {
                changes.add(new TaxonChange(ADD, after.get(id).rank, List.of(), List.of(id)));
            }
        }
        Map<String, List<String>> byTarget = new TreeMap<>(Comparator.comparing(Step::idOrder));
        merged.forEach((source, target) -> byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(source));
        byTarget.forEach((target, sources) -> changes.add(new TaxonChange(MERGE, before.get(sources.getFirst()).rank,
                List.copyOf(sources), List.of(target))));
        splits.forEach((source, to) -> changes.add(new TaxonChange(SPLIT, before.get(source).rank, List.of(source), to)));
        for (Tree.Node n : before.all()) {
            if (n.official() && n.active && !listed.containsKey(n.id) && !merged.containsKey(n.id) && !splits.containsKey(n.id)) {
                changes.add(new TaxonChange(REMOVE, n.rank, List.of(n.id), List.of()));
            }
        }
    }

    /** {@code A9} before {@code A10}. */
    static String idOrder(String id) {
        String digits = id.replaceAll("\\D", "");
        return id.replaceAll("\\d", "") + String.format(Locale.ROOT, "%012d", digits.isEmpty() ? 0 : Long.parseLong(digits));
    }

    /** The bundle's taxa, parents first by its own rank order. */
    private List<BundleTaxon> topDown() {
        List<String> ranks = bundle.ranks();
        for (BundleTaxon t : bundle.taxa()) {
            if (!ranks.contains(t.rank())) {
                throw new IllegalStateException("The taxonomy file is inconsistent (taxon " + t.id() + " has unknown rank "
                        + t.rank() + ")");
            }
        }
        return bundle.taxa().stream().sorted(Comparator.comparingInt((BundleTaxon t) -> ranks.indexOf(t.rank()))).toList();
    }

    /**
     * The live photos whose file moves, in the order of their ids: a photo keeps its file name in its species' new
     * folder, a renamed species' {@code <Old>_NN.ext} becoming {@code <New>_NN.ext} (a file named by hand keeps its
     * name); a merged species' photos get the target's next free numbers, skipping a name another photo has or will
     * have (without case) or {@code takenOnDisk} says is there, and the target's next number moves on in
     * {@link #after}. A photo whose new path another photo already has or is given (without case: two files named by hand, a
     * hand-named {@code New_01.jpg} beside the app's {@code Old_01.jpg}) gets its species' next free number in the same
     * way.
     */
    List<Moved> moves(List<Model.Live> live, Predicate<String> takenOnDisk) {
        List<Moved> moves = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        live.forEach(p -> taken.add(fold(p.relPath())));
        // The paths that stay, and those given so far: a new path among them is given a free name instead.
        Set<String> given = new HashSet<>();
        Map<Model.Live, String> wanted = new LinkedHashMap<>();
        Map<String, List<Model.Live>> mergedIn = new TreeMap<>(Comparator.comparing(Step::idOrder));
        for (Model.Live p : live) {
            if (merged.containsKey(p.taxonId())) {
                mergedIn.computeIfAbsent(merged.get(p.taxonId()), k -> new ArrayList<>()).add(p);
                continue;
            }
            Tree.Node was = before.get(p.taxonId());
            String folder = after.folder(p.taxonId());
            if (was == null || folder == null) {
                continue;
            }
            String name = p.number() == null ? p.fileName() : renamed(p.fileName(), p.number(), was.name, after.get(p.taxonId()).name);
            String newRel = folder + name;
            if (newRel.equals(p.relPath())) {
                given.add(fold(newRel));
            } else {
                wanted.put(p, name);
            }
        }
        for (Map.Entry<Model.Live, String> entry : wanted.entrySet()) {
            Model.Live p = entry.getKey();
            String folder = after.folder(p.taxonId());
            String newRel = folder + entry.getValue();
            if (!given.contains(fold(newRel))) {
                moves.add(new Moved(p.id(), p.taxonId(), p.relPath(), newRel, entry.getValue(), null));
            } else {
                Tree.Node species = after.get(p.taxonId());
                int n = Math.max(1, species.next);
                String extension = extension(p.fileName());
                while (true) {
                    String name = FileNames.photoName(species.name, n, extension);
                    newRel = folder + name;
                    n++;
                    if (!taken.contains(fold(newRel)) && !given.contains(fold(newRel)) && !wanted.containsValue(name)
                            && !takenOnDisk.test(newRel)) {
                        moves.add(new Moved(p.id(), p.taxonId(), p.relPath(), newRel, name, n - 1));
                        break;
                    }
                }
                species.next = n;
            }
            given.add(fold(newRel));
            taken.add(fold(newRel));
        }
        for (Map.Entry<String, List<Model.Live>> entry : mergedIn.entrySet()) {
            Tree.Node target = after.get(entry.getKey());
            String folder = after.folder(target.id);
            if (folder == null) {
                continue; // a higher rank: its species have no photos of their own to move here
            }
            List<Model.Live> photos = new ArrayList<>(entry.getValue());
            photos.sort(Comparator.comparing((Model.Live p) -> idOrder(p.taxonId()))
                    .thenComparing(p -> p.number() == null ? Integer.MAX_VALUE : p.number())
                    .thenComparingLong(Model.Live::id));
            int n = Math.max(1, target.next);
            for (Model.Live p : photos) {
                String extension = extension(p.fileName());
                while (true) {
                    String name = FileNames.photoName(target.name, n, extension);
                    String newRel = folder + name;
                    n++;
                    if (!taken.contains(fold(newRel)) && !takenOnDisk.test(newRel)) {
                        moves.add(new Moved(p.id(), target.id, p.relPath(), newRel, name, n - 1));
                        taken.add(fold(newRel));
                        break;
                    }
                }
            }
            target.next = n;
        }
        moves.sort(Comparator.comparingLong(Moved::imageId));
        return moves;
    }

    /**
     * Photos in Recently deleted: a renamed species' {@code <Old>_NN.ext} restore name becomes {@code <New>_NN.ext}; a
     * merged species' photos go to the target with its next numbers (after its live photos': call {@link #moves} first).
     * Their files in {@code .deleted/} do not move.
     */
    List<Renamed> deleted(List<Model.Deleted> deleted) {
        List<Renamed> renamed = new ArrayList<>();
        for (Model.Deleted d : deleted) {
            String target = merged.get(d.taxonId());
            if (target != null) {
                Tree.Node t = after.get(target);
                if (t.species()) {
                    int n = Math.max(1, t.next);
                    t.next = n + 1;
                    renamed.add(new Renamed(d.id(), target, FileNames.photoName(t.name, n, extension(d.fileName())), n));
                }
                continue;
            }
            Tree.Node was = before.get(d.taxonId());
            Tree.Node now = after.get(d.taxonId());
            if (was == null || now == null || d.number() == null || was.name.equals(now.name)) {
                continue;
            }
            String name = renamed(d.fileName(), d.number(), was.name, now.name);
            if (!name.equals(d.fileName())) {
                renamed.add(new Renamed(d.id(), d.taxonId(), name, d.number()));
            }
        }
        return renamed;
    }

    /** The live photos of split species (still on them) with the species they are on. */
    Map<Long, String> splitPhotos(List<Model.Live> live) {
        Map<Long, String> photos = new LinkedHashMap<>();
        for (Model.Live p : live) {
            if (splits.containsKey(p.taxonId())) {
                photos.put(p.id(), p.taxonId());
            }
        }
        return photos;
    }

    /** The library after this step: the new tree, the photos where the moves put them, the restore names given. */
    Model next(Model model, List<Moved> moves, List<Renamed> renamed) {
        Map<Long, Moved> byId = new HashMap<>();
        moves.forEach(m -> byId.put(m.imageId(), m));
        List<Model.Live> live = new ArrayList<>();
        for (Model.Live p : model.live()) {
            Moved m = byId.get(p.id());
            live.add(m == null ? p : new Model.Live(p.id(), m.taxonId(), m.newRel(), m.newFileName(),
                    m.newNumber() == null ? p.number() : m.newNumber()));
        }
        Map<Long, Renamed> names = new HashMap<>();
        renamed.forEach(r -> names.put(r.imageId(), r));
        List<Model.Deleted> deleted = new ArrayList<>();
        for (Model.Deleted d : model.deleted()) {
            Renamed r = names.get(d.id());
            deleted.add(r == null ? d : new Model.Deleted(d.id(), r.taxonId(), r.fileName(), r.number()));
        }
        return new Model(after, live, deleted);
    }

    /** {@code fileName} with the new name when it is the app's {@code <oldName>_NN.ext}, else as it is. */
    static String renamed(String fileName, int number, String oldName, String newName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName;
        }
        String extension = fileName.substring(dot + 1);
        return fileName.equals(FileNames.photoName(oldName, number, extension))
                ? FileNames.photoName(newName, number, extension) : fileName;
    }

    /** The extension the app gives this file ({@code jpg} for {@code IMG_1.JPEG}), as Move does. */
    static String extension(String fileName) {
        return PhotoFormat.fromFileName(fileName).map(PhotoFormat::extension).orElseGet(() -> {
            int dot = fileName.lastIndexOf('.');
            return dot < 0 ? "jpg" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        });
    }

    static String fold(String relPath) {
        return relPath.toLowerCase(Locale.ROOT);
    }
}
