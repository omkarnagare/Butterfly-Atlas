package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.updates.UpdateViews.Item;
import com.butterflyatlas.app.updates.UpdateViews.Kind;
import com.butterflyatlas.app.updates.UpdateViews.Ref;
import com.butterflyatlas.app.updates.UpdateViews.Species;
import com.butterflyatlas.app.updates.UpdateViews.Summary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The summary of a pending update (5b contract §2, §5): the library's taxa and live photos taken through every step of
 * the chain in memory, nothing written. A combined chain has one item per id and kind, with the names before the first
 * step and after the last.
 */
final class DryRun {

    private static final List<String> KINDS = List.of(Step.RENAME, Step.MOVE, Step.MERGE, Step.SPLIT, Step.ADD, Step.REMOVE);

    /** One item being gathered over the steps. */
    private static final class Gathered {
        final String kind;
        final String rank;
        final Set<String> from = new LinkedHashSet<>();
        final Set<String> to = new LinkedHashSet<>();
        final Set<Long> photos = new HashSet<>();
        /** For a move: the parent before the first step it moved in. */
        String parentBefore;

        Gathered(String kind, String rank) {
            this.kind = kind;
            this.rank = rank;
        }
    }

    private DryRun() {
    }

    static Summary summary(Model start, List<Bundle> steps) {
        Map<String, Gathered> items = new LinkedHashMap<>();
        Set<Long> affected = new HashSet<>();
        Map<String, Ref> firstSeen = new LinkedHashMap<>();
        Model model = start;
        for (Bundle bundle : steps) {
            Step step = Step.of(model.tree(), bundle);
            List<Step.Moved> moves = step.moves(model.live(), path -> false);
            List<Step.Renamed> renamed = step.deleted(model.deleted());
            moves.forEach(m -> affected.add(m.imageId()));
            affected.addAll(step.splitPhotos(model.live()).keySet());
            for (Tree.Node n : model.tree().all()) {
                firstSeen.putIfAbsent(n.id, new Ref(n.id, n.name, n.common));
            }
            for (Tree.Node n : step.after.all()) {
                firstSeen.putIfAbsent(n.id, new Ref(n.id, n.name, n.common));
            }
            for (Step.TaxonChange change : step.changes) {
                String kind = Step.REACTIVATE.equals(change.kind()) ? Step.ADD : change.kind();
                String key = kind + "|" + switch (kind) {
                    case Step.MERGE -> change.to().getFirst();
                    case Step.SPLIT -> change.from().getFirst();
                    default -> change.from().isEmpty() ? change.to().getFirst() : change.from().getFirst();
                };
                Gathered item = items.computeIfAbsent(key, k -> new Gathered(kind, change.rank()));
                item.from.addAll(change.from());
                item.to.addAll(change.to());
                if (Step.MOVE.equals(kind) && item.parentBefore == null) {
                    item.parentBefore = model.tree().get(change.from().getFirst()).parentId;
                }
                List<String> roots = Step.MERGE.equals(kind) || Step.SPLIT.equals(kind) || Step.REMOVE.equals(kind)
                        ? change.from() : change.to();
                Tree tree = model.tree();
                for (Model.Live p : model.live()) {
                    if (tree.has(p.taxonId()) && roots.stream().anyMatch(r -> tree.has(r) && tree.under(p.taxonId(), r))) {
                        item.photos.add(p.id());
                        if (Step.REMOVE.equals(change.kind()) || Step.REACTIVATE.equals(change.kind())) {
                            affected.add(p.id()); // its species is taken off the list, or back on it
                        }
                    }
                }
            }
            model = step.next(model, moves, renamed);
        }
        Tree end = model.tree();
        Map<String, List<Item>> byKind = new LinkedHashMap<>();
        Map<String, Set<Long>> photosByKind = new LinkedHashMap<>();
        for (Gathered g : items.values()) {
            List<Ref> from = g.from.stream().map(id -> firstSeen.get(id)).filter(Objects::nonNull).toList();
            List<Ref> to = g.to.stream().filter(end::has).map(id -> ref(end, id)).toList();
            String detail = detail(g, from, to, end, firstSeen);
            if (detail == null) {
                continue; // back as it was by the end of the chain
            }
            byKind.computeIfAbsent(g.kind, k -> new ArrayList<>()).add(new Item(g.rank, from, to, g.photos.size(), detail));
            photosByKind.computeIfAbsent(g.kind, k -> new HashSet<>()).addAll(g.photos);
        }
        List<Kind> kinds = new ArrayList<>();
        for (String kind : KINDS) {
            List<Item> list = byKind.get(kind);
            if (list == null) {
                continue;
            }
            List<Item> sorted = list.stream().sorted(Comparator.comparingInt((Item i) -> -i.photos())
                    .thenComparing(i -> name(i).toLowerCase(Locale.ROOT))).toList();
            kinds.add(new Kind(kind, sorted.size(), photosByKind.get(kind).size(), sorted));
        }
        return new Summary(affected.size(), new Species(species(start), species(model)), kinds);
    }

    private static Ref ref(Tree tree, String id) {
        Tree.Node n = tree.get(id);
        return new Ref(n.id, n.name, n.common);
    }

    /** The name an item is sorted by: the merge's target, else the first taxon it names. */
    private static String name(Item item) {
        if (!item.to().isEmpty() && (item.from().isEmpty() || item.from().size() > 1)) {
            return item.to().getFirst().name();
        }
        return item.from().isEmpty() ? item.to().getFirst().name() : item.from().getFirst().name();
    }

    /** The plain words of an item ("Euploea core → Euploea coreta"); null when the chain brought it back as it was. */
    private static String detail(Gathered g, List<Ref> from, List<Ref> to, Tree end, Map<String, Ref> firstSeen) {
        String names = from.stream().map(Ref::name).collect(Collectors.joining(", "));
        String targets = to.stream().map(Ref::name).collect(Collectors.joining(", "));
        return switch (g.kind) {
            case Step.RENAME -> from.getFirst().name().equals(to.getFirst().name()) ? null : names + " → " + targets;
            case Step.MOVE -> {
                String id = g.to.iterator().next();
                String parentAfter = end.get(id).parentId;
                if (Objects.equals(g.parentBefore, parentAfter)) {
                    yield null;
                }
                Ref before = firstSeen.get(g.parentBefore);
                yield end.get(id).name + ": " + (before == null ? g.parentBefore : before.name()) + " → "
                        + end.get(parentAfter).name;
            }
            case Step.MERGE -> names + " merged into " + targets;
            case Step.SPLIT -> names + " split into " + targets;
            case Step.ADD -> targets + " added";
            default -> names + " removed";
        };
    }

    /** Species shown (active or photographed) and photographed, in the whole group (custom species included; §11). */
    static Count species(Model model) {
        Set<String> photographed = model.live().stream().map(Model.Live::taxonId).collect(Collectors.toSet());
        int got = 0;
        int total = 0;
        for (Tree.Node n : model.tree().all()) {
            if (!n.species()) {
                continue;
            }
            boolean has = photographed.contains(n.id);
            if (n.active || has) {
                total++;
                if (has) {
                    got++;
                }
            }
        }
        return new Count(got, total);
    }
}
