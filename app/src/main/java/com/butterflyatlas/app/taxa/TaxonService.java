package com.butterflyatlas.app.taxa;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoQueries.Cover;
import com.butterflyatlas.app.photos.PhotoQueries.Stages;
import com.butterflyatlas.app.taxonomy.BundleLoader;
import com.butterflyatlas.app.taxa.TaxonSnapshot.Row;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxa.TaxonViews.Crumb;
import com.butterflyatlas.app.taxa.TaxonViews.RecentPhoto;
import com.butterflyatlas.app.taxa.TaxonViews.Summary;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonCard;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonDetail;
import com.butterflyatlas.app.taxa.TaxonViews.TreeNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Tree browsing with counts (docs/PLAN.md §8, §11), and the photo facts Home and the species page show. */
public final class TaxonService {

    public static final int RECENT = 12;
    public static final int NEXT_ON_LIST = 8;

    private final Supplier<Database> database;

    public TaxonService(Supplier<Database> database) {
        this.database = database;
    }

    public TaxonDetail taxon(String id) {
        return database.get().read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            return detail(c, snapshot, visible(snapshot, id));
        });
    }

    public List<TaxonCard> children(String id) {
        return database.get().read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            Row parent = visible(snapshot, id);
            Map<String, Cover> covers = PhotoQueries.covers(c, parent.id());
            return snapshot.visibleChildren(parent.id()).stream().map(child -> card(snapshot, covers, child)).toList();
        });
    }

    public Summary summary() {
        return database.get().read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            Map<String, Cover> covers = PhotoQueries.covers(c);
            Row group = root(snapshot);
            List<TaxonCard> families = shown(snapshot, group).stream().map(child -> card(snapshot, covers, child)).toList();
            List<RecentPhoto> recent = new ArrayList<>();
            for (Photo photo : PhotoQueries.recent(c, RECENT)) {
                snapshot.find(photo.taxonId())
                        .ifPresent(row -> recent.add(new RecentPhoto(photo, card(snapshot, covers, row))));
            }
            return new Summary(detail(c, snapshot, group), families, PhotoQueries.liveCount(c),
                    recent.isEmpty() ? null : recent.get(0), recent, nextOnList(snapshot, covers),
                    fullLifeCycles(c, snapshot, covers));
        });
    }

    public TreeNode tree() {
        return database.get().read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            return node(snapshot, root(snapshot));
        });
    }

    /**
     * Up to 8 species still to photograph, from the genera with the highest share already
     * photographed (then the most photographed, then by name). Genera with none, or all, are skipped.
     */
    private static List<TaxonCard> nextOnList(TaxonSnapshot snapshot, Map<String, Cover> covers) {
        List<Row> genera = snapshot.visibleOfRank("genus").stream()
                .filter(g -> snapshot.count(g.id()).got() > 0 && snapshot.count(g.id()).got() < snapshot.count(g.id()).total())
                .sorted(Comparator.comparingDouble((Row g) -> -share(snapshot.count(g.id())))
                        .thenComparingInt(g -> -snapshot.count(g.id()).got())
                        .thenComparing(g -> g.name().toLowerCase(Locale.ROOT)))
                .toList();
        List<TaxonCard> next = new ArrayList<>();
        for (Row genus : genera) {
            for (Row species : snapshot.visibleChildren(genus.id())) {
                if (next.size() == NEXT_ON_LIST) {
                    return next;
                }
                if ("species".equals(species.rank()) && !species.photographed()) {
                    next.add(card(snapshot, covers, species));
                }
            }
        }
        return next;
    }

    private static double share(Count count) {
        return (double) count.got() / count.total();
    }

    private static List<TaxonCard> fullLifeCycles(Connection c, TaxonSnapshot snapshot, Map<String, Cover> covers)
            throws SQLException {
        return PhotoQueries.allStages(c).entrySet().stream()
                .filter(e -> e.getValue().full())
                .map(e -> snapshot.find(e.getKey()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing((Row r) -> r.name().toLowerCase(Locale.ROOT)).thenComparing(Row::id))
                .map(row -> card(snapshot, covers, row))
                .toList();
    }

    /** The visible children, Custom additions only while it has a species shown (Home's families, the tree). */
    private static List<Row> shown(TaxonSnapshot snapshot, Row parent) {
        return snapshot.visibleChildren(parent.id()).stream()
                .filter(child -> !TaxonSnapshot.ADDITIONS.equals(child.rank()) || snapshot.count(child.id()).total() > 0)
                .toList();
    }

    private static Row visible(TaxonSnapshot snapshot, String id) {
        return snapshot.find(id).filter(snapshot::visible)
                .orElseThrow(() -> AtlasException.noTaxon(id));
    }

    private static Row root(TaxonSnapshot snapshot) {
        // One group today (butterflies); host plants and predators will add more roots later.
        return snapshot.find(BundleLoader.groupId("butterflies"))
                .orElseThrow(() -> AtlasException.notFound("Your atlas has no butterflies yet."));
    }

    private static TaxonCard card(TaxonSnapshot snapshot, Map<String, Cover> covers, Row row) {
        return new TaxonCard(row.id(), row.rank(), row.name(), row.common(), snapshot.family(row), row.active(),
                row.source(), snapshot.count(row.id()), "species".equals(row.rank()) ? covers.get(row.id()) : null, row.toSort());
    }

    private static TreeNode node(TaxonSnapshot snapshot, Row row) {
        List<TreeNode> kids = new ArrayList<>();
        for (Row child : shown(snapshot, row)) {
            kids.add(node(snapshot, child));
        }
        return new TreeNode(row.id(), row.rank(), row.name(), row.common(), snapshot.family(row), row.source(),
                snapshot.count(row.id()), kids);
    }

    private static TaxonDetail detail(Connection c, TaxonSnapshot snapshot, Row row) throws SQLException {
        List<Crumb> path = snapshot.ancestors(row).stream()
                .map(a -> new Crumb(a.id(), a.rank(), a.name(), a.common())).toList();
        boolean species = "species".equals(row.rank());
        return new TaxonDetail(row.id(), row.rank(), row.name(), row.common(), snapshot.family(row), row.familyId(),
                row.active(), row.source(), path,
                snapshot.count(row.id()), snapshot.ranksBelow(row), names(c, row.id(), "bundle"),
                names(c, row.id(), "user"), snapshot.folder(row), snapshot.photoCount(row.id()),
                species ? PhotoQueries.stages(c, row.id()) : Stages.NONE,
                species ? PhotoQueries.cover(c, row.id()).orElse(null) : null, row.toSort());
    }

    /** A taxon's synonyms of one source ({@code bundle}, or {@code user}: its search words), by name. */
    public static List<String> names(Connection c, String id, String source) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM taxon_synonym WHERE taxon_id = ? AND source = ? ORDER BY name COLLATE NOCASE")) {
            ps.setString(1, id);
            ps.setString(2, source);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }
}
