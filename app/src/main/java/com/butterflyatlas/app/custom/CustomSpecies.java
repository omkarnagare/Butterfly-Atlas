package com.butterflyatlas.app.custom;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.imports.ImportSpeciesChoices;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.OpenImports;
import com.butterflyatlas.app.photos.FileNames;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.photos.TaxonRef;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonDetail;
import com.butterflyatlas.app.taxonomy.BundleLoader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The user's own species, under the group's Custom additions (5a contract §2, §3; design D3–D7): a confirmed species the
 * bundled list lacks, with its family, scientific name and optional common name. Ids {@code U1}, {@code U2}, … are never
 * reused. Counted in the group's totals only, never in its family's.
 */
public final class CustomSpecies {

    static final String ADD = "taxon.add";
    static final String RENAME = "taxon.rename";
    static final String DELETE = "taxon.delete";
    static final String GROUP = "butterflies";

    /**
     * The answer of a name check: {@code ok} with the name as it would be stored, or the reason it cannot be used
     * ({@code invalid}, {@code in_list}, {@code name_exists}) with the species that has it (null for {@code invalid}).
     */
    public record Check(boolean ok, String name, String code, String message, TaxonRef taxon) {
    }

    private final Supplier<Library> library;
    private final TaxonService taxa;
    private final Clock clock;
    private final List<Runnable> changed = new CopyOnWriteArrayList<>();

    public CustomSpecies(Supplier<Library> library, TaxonService taxa, Clock clock) {
        this.library = library;
        this.taxa = taxa;
        this.clock = clock;
    }

    /** Whether {@code rawName} can be the name of a new custom species, or of {@code id} (renamed); null for none. */
    public Check check(String rawName, String id) {
        String name;
        try {
            name = CustomNames.name(rawName);
        } catch (AtlasException e) {
            return new Check(false, null, e.code(), e.getMessage(), null);
        }
        try {
            library.get().database().read(c -> {
                CustomNames.requireFree(c, name, id);
                return null;
            });
        } catch (NameBlocked e) {
            return new Check(false, null, e.code(), e.getMessage(), e.taxon());
        }
        return new Check(true, name, null, null, null);
    }

    /**
     * Adds a custom species of the official family {@code familyId}. Adding, renaming and deleting are refused while a
     * taxonomy update runs, waits or is paused.
     */
    public TaxonDetail add(String familyId, String rawName, String rawCommon) {
        return changed(library.get().gate().whileOpen(() -> added(familyId, rawName, rawCommon)));
    }

    /** Runs {@code listener} after each add, rename and delete (a taxonomy update's match count follows them). */
    public void onChange(Runnable listener) {
        changed.add(listener);
    }

    private <T> T changed(T result) {
        changed.forEach(Runnable::run);
        return result;
    }

    private TaxonDetail added(String familyId, String rawName, String rawCommon) {
        String name = CustomNames.name(rawName);
        String common = CustomNames.common(rawCommon);
        String id = library.get().database().write(c -> {
            requireFamily(c, familyId);
            CustomNames.requireFree(c, name, null);
            String added = "U" + (lastNumber(c) + 1);
            String additions = BundleLoader.additionsId(GROUP);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxon (id, parent_id, rank, name, common_name,"
                    + " group_key, source, inat_id, ancestor_path, folder_name, active, sort_key, family_id)"
                    + " VALUES (?, ?, 'species', ?, ?, ?, 'manual', NULL, ?, ?, 1, ?, ?)")) {
                ps.setString(1, added);
                ps.setString(2, additions);
                ps.setString(3, name);
                ps.setString(4, common);
                ps.setString(5, GROUP);
                ps.setString(6, "/" + BundleLoader.groupId(GROUP) + "/" + additions + "/" + added + "/");
                ps.setString(7, folderName(name, added));
                ps.setString(8, sortKey(name));
                ps.setString(9, familyId);
                ps.executeUpdate();
            }
            index(c, added, name, common);
            ChangeLog.record(c, clock.instant(), ADD, ChangeLog.payload("id", added, "name", name, "common", common,
                    "familyId", familyId));
            return added;
        });
        return taxa.taxon(id);
    }

    /**
     * Renames a custom species. A change of the common name only moves nothing; a new scientific name moves its photos
     * to the new folder in the same write, their files renamed (see {@link SpeciesFiles}), and removes the old folder
     * when that leaves it empty.
     */
    public TaxonDetail rename(String id, String rawName, String rawCommon) {
        Library lib = library.get();
        return changed(lib.gate().whileOpen(() -> movesFolder(lib, id, rawName)
                ? lib.photoHold().whileFree(() -> renamed(id, rawName, rawCommon)) : renamed(id, rawName, rawCommon)));
    }

    /** Whether a rename gives a new scientific name, so the folder moves (refused while a backup holds the photos). */
    private static boolean movesFolder(Library lib, String id, String rawName) {
        String name = CustomNames.name(rawName);
        return lib.database().read(c -> !custom(c, id).name().equals(name));
    }

    private TaxonDetail renamed(String id, String rawName, String rawCommon) {
        String name = CustomNames.name(rawName);
        String common = CustomNames.common(rawCommon);
        Library lib = library.get();
        SpeciesFiles files = new SpeciesFiles(lib.folder());
        boolean moved = lib.database().write(c -> {
            Row old = custom(c, id);
            boolean renamed = !old.name().equals(name);
            if (!renamed && Objects.equals(old.common(), common)) {
                return false;
            }
            if (renamed) {
                CustomNames.requireFree(c, name, id);
            }
            String oldFolder = folder(c, id);
            try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET name = ?, common_name = ?, sort_key = ?,"
                    + " folder_name = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, common);
                ps.setString(3, sortKey(name));
                ps.setString(4, folderName(name, id));
                ps.setString(5, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM taxon_fts WHERE taxon_id = ? AND kind IN ('name', 'common')")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            index(c, id, name, common);
            Instant now = clock.instant();
            if (renamed) {
                files.rename(c, id, oldFolder, folder(c, id), old.name(), name, now);
            }
            ChangeLog.record(c, now, RENAME, ChangeLog.payload("id", id, "from", old.name(), "to", name,
                    "fromCommon", old.common(), "toCommon", common));
            return renamed;
        }, files::undo);
        if (moved) {
            files.removeOldFolder();
        }
        return taxa.taxon(id);
    }

    /**
     * Deletes a custom species that has no photos, live or in Recently deleted (else 409 {@code has_photos}). Import
     * items that named it forget it; its search words go with it; its empty folder is removed. Its id is never given
     * again; past exports keep it with the name stored with them.
     */
    public void delete(String id) {
        Library lib = library.get();
        lib.gate().whileOpen(() -> lib.photoHold().whileFree(() -> deleted(id)));
        changed(null);
    }

    private void deleted(String id) {
        Library lib = library.get();
        String folder;
        // the open imports whose items lose the species: marked while the write runs, their revisions up once it committed
        try (OpenImports.Change change = lib.openImports().change()) {
            folder = deletedRow(lib, id, change);
            change.committed();
        }
        SpeciesFiles.removeIfEmpty(lib.folder(), folder);
    }

    private String deletedRow(Library lib, String id, OpenImports.Change change) {
        return lib.database().write(c -> {
            Row row = custom(c, id);
            lib.openImports().refuseWhileFiling(ImportSpeciesChoices.choosing(c, id), OpenImports.SPECIES_FILING);
            requireNoPhotos(c, id);
            String gone = folder(c, id);
            Instant now = clock.instant();
            change.add(ImportSpeciesChoices.deleted(c, id, now));
            List<String> words = TaxonService.names(c, id, "user");
            for (String sql : new String[] {"DELETE FROM taxon_synonym WHERE taxon_id = ?", "DELETE FROM taxon_fts WHERE taxon_id = ?",
                    "DELETE FROM taxon WHERE id = ?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
            ChangeLog.record(c, now, DELETE, ChangeLog.payload("id", id, "name", row.name(), "common", row.common(),
                    "familyId", row.familyId(), "words", words));
            return gone;
        });
    }

    /** 409 {@code has_photos} while any photo, live or in Recently deleted, is of the species. */
    private static void requireNoPhotos(Connection c, String id) throws SQLException {
        int live = 0;
        int deleted = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT deleted_at IS NULL, COUNT(*) FROM image WHERE taxon_id = ?"
                + " GROUP BY deleted_at IS NULL")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt(1) == 1) {
                        live = rs.getInt(2);
                    } else {
                        deleted = rs.getInt(2);
                    }
                }
            }
        }
        if (live == 0 && deleted == 0) {
            return;
        }
        String message;
        if (deleted == 0) {
            message = "Move " + its(live) + " first.";
        } else if (live == 0) {
            message = "Empty or restore " + its(deleted) + " in Recently deleted first.";
        } else {
            message = "Move " + its(live) + " and empty or restore " + String.format(Locale.ENGLISH, "%,d", deleted)
                    + " in Recently deleted first.";
        }
        throw new AtlasException(AtlasException.Kind.CONFLICT, "has_photos", message);
    }

    /** "its photo", "its 4 photos", "its 1,340 photos" (as the UI counts; fixes J11). */
    static String its(int photos) {
        return photos == 1 ? "its photo" : "its " + String.format(Locale.ENGLISH, "%,d", photos) + " photos";
    }

    /** A custom species' row. */
    record Row(String id, String name, String common, String familyId) {
    }

    /** The custom species {@code id}, or 404 {@code not_found}. */
    static Row custom(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name, common_name, family_id FROM taxon WHERE id = ?"
                + " AND source = 'manual' AND rank = 'species'")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AtlasException.notFound("There is no custom species " + id + " in your atlas.");
                }
                return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    /** The species' folder as it is now, relative to the data folder. */
    private static String folder(Connection c, String id) throws SQLException {
        TaxonSnapshot snapshot = TaxonSnapshot.load(c);
        return snapshot.folder(snapshot.find(id).orElseThrow());
    }

    /** {@code Neptis_sankara_[U3]}. */
    static String folderName(String name, String id) {
        return FileNames.safe(name + " [" + id + "]");
    }

    static String sortKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The search rows of a custom species: its name and, when it has one, its common name. */
    static void index(Connection c, String id, String name, String common) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, "name");
            ps.setString(3, name);
            ps.executeUpdate();
            if (common != null) {
                ps.setString(2, "common");
                ps.setString(3, common);
                ps.executeUpdate();
            }
        }
    }

    /** 400 {@code invalid} unless {@code familyId} is an active official family. */
    private static void requireFamily(Connection c, String familyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM taxon WHERE id = ? AND rank = 'family'"
                + " AND source = 'official' AND active = 1")) {
            ps.setString(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw CustomNames.invalid("Choose the species' family.");
                }
            }
        }
    }

    /**
     * The highest custom species number ever given: those in the library and those added and deleted (or merged) since
     * (change_log; a taxonomy update's {@code taxon.add} of an official {@code A…} id does not count).
     */
    private static long lastNumber(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT max(n) FROM ("
                + "SELECT CAST(substr(id, 2) AS INTEGER) AS n FROM taxon WHERE id GLOB 'U[0-9]*'"
                + " UNION ALL SELECT CAST(substr(json_extract(payload_json, '$.id'), 2) AS INTEGER) FROM change_log"
                + " WHERE kind = ? AND json_extract(payload_json, '$.id') GLOB 'U[0-9]*')")) {
            ps.setString(1, ADD);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
