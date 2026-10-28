package com.butterflyatlas.app.taxonomy;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.photos.FileNames;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a bundle into a new library in one transaction: the group root ({@code G:<key>}), its
 * "Custom additions" child ({@code U:<key>}), every taxon with its ancestor path and folder name
 * ({@code Danaus_chrysippus_[A5]}, made safe by {@link FileNames#safe}),
 * bundle synonyms, the search index, and a {@code taxonomy_state} row.
 */
public final class BundleLoader {

    public static final String GROUP_RANK = "group";
    public static final String SPECIES_RANK = "species";
    public static final String ADDITIONS_RANK = "additions";
    public static final String ADDITIONS_NAME = "Custom additions";

    private static final String INSERT_TAXON = "INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source,"
            + " inat_id, ancestor_path, folder_name, active, sort_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)";
    private static final String INSERT_SYNONYM = "INSERT OR IGNORE INTO taxon_synonym (taxon_id, name, source) VALUES (?, ?, 'bundle')";
    private static final String INSERT_SEARCH = "INSERT INTO taxon_fts (taxon_id, kind, text) VALUES (?, ?, ?)";

    private BundleLoader() {
    }

    public static String groupId(String groupKey) {
        return "G:" + groupKey;
    }

    public static String additionsId(String groupKey) {
        return "U:" + groupKey;
    }

    public static void load(Database db, TaxonomyBundles.Shipped shipped, Clock clock) {
        Bundle bundle = shipped.bundle();
        db.write(c -> {
            requireEmpty(c);
            String key = bundle.group().key();
            String groupId = groupId(key);
            String groupPath = "/" + groupId + "/";
            try (PreparedStatement taxon = c.prepareStatement(INSERT_TAXON);
                 PreparedStatement synonym = c.prepareStatement(INSERT_SYNONYM);
                 PreparedStatement search = c.prepareStatement(INSERT_SEARCH)) {
                insert(taxon, groupId, null, GROUP_RANK, bundle.group().name(), null, key, "system",
                        bundle.group().inatTaxonId(), groupPath, bundle.group().name());
                index(search, groupId, "name", bundle.group().name());
                String additions = additionsId(key);
                insert(taxon, additions, groupId, ADDITIONS_RANK, ADDITIONS_NAME, null, key, "system", null,
                        groupPath + additions + "/", FileNames.safe(ADDITIONS_NAME));

                Map<String, String> paths = new HashMap<>();
                Map<String, String> ranks = new HashMap<>();
                for (BundleTaxon t : topDown(bundle)) {
                    String parentPath = t.parent() == null ? groupPath : paths.get(t.parent());
                    if (parentPath == null) {
                        throw inconsistent("taxon " + t.id() + " has unknown parent " + t.parent());
                    }
                    if (t.parent() != null && bundle.ranks().indexOf(ranks.get(t.parent())) >= bundle.ranks().indexOf(t.rank())) {
                        throw inconsistent("taxon " + t.id() + " is ranked above its parent " + t.parent());
                    }
                    ranks.put(t.id(), t.rank());
                    String path = parentPath + t.id() + "/";
                    paths.put(t.id(), path);
                    String folder = FileNames.safe(SPECIES_RANK.equals(t.rank()) ? t.name() + " [" + t.id() + "]" : t.name());
                    insert(taxon, t.id(), t.parent() == null ? groupId : t.parent(), t.rank(), t.name(), t.common(), key,
                            "official", t.inatId(), path, folder);
                    index(search, t.id(), "name", t.name());
                    if (t.common() != null) {
                        index(search, t.id(), "common", t.common());
                    }
                    for (String name : t.synonyms()) {
                        synonym.setString(1, t.id());
                        synonym.setString(2, name);
                        if (synonym.executeUpdate() > 0) {
                            index(search, t.id(), "synonym", name);
                        }
                    }
                }
            }
            try (PreparedStatement state = c.prepareStatement(
                    "INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES (?, ?, ?)")) {
                state.setString(1, bundle.version());
                state.setString(2, shipped.sha256());
                state.setString(3, clock.instant().toString());
                state.executeUpdate();
            }
            return null;
        });
    }

    private static BundleException inconsistent(String detail) {
        return new BundleException("The taxonomy file is inconsistent (" + detail + "). Reinstall Butterfly Atlas.");
    }

    private static void requireEmpty(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM taxonomy_state"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) > 0) {
                throw new IllegalStateException("This library already has a taxonomy; updates are applied, not reloaded");
            }
        }
    }

    /** Parents before children, using the bundle's own rank order. */
    private static List<BundleTaxon> topDown(Bundle bundle) {
        for (BundleTaxon t : bundle.taxa()) {
            if (!bundle.ranks().contains(t.rank())) {
                throw inconsistent("taxon " + t.id() + " has unknown rank " + t.rank());
            }
        }
        return bundle.taxa().stream()
                .sorted(Comparator.comparingInt((BundleTaxon t) -> bundle.ranks().indexOf(t.rank())))
                .toList();
    }

    private static void insert(PreparedStatement ps, String id, String parent, String rank, String name, String common,
                               String groupKey, String source, Long inatId, String path, String folder) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, parent);
        ps.setString(3, rank);
        ps.setString(4, name);
        ps.setString(5, common);
        ps.setString(6, groupKey);
        ps.setString(7, source);
        if (inatId == null) {
            ps.setNull(8, Types.INTEGER);
        } else {
            ps.setLong(8, inatId);
        }
        ps.setString(9, path);
        ps.setString(10, folder);
        ps.setString(11, name.toLowerCase(Locale.ROOT));
        ps.executeUpdate();
    }

    private static void index(PreparedStatement ps, String id, String kind, String text) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, kind);
        ps.setString(3, text);
        ps.executeUpdate();
    }
}
