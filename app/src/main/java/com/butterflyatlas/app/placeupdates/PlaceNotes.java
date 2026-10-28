package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.places.PlaceChange;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.PlaceStore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The Home note of a place update (5c contract §6, §9; design P7, P8): what the steps not yet dismissed changed of the
 * library, the user places merged, the photos the GPS re-check moved, the photos left in former places (by species,
 * shown again after Dismiss while any is left) and those the re-check could not do; and its part of {@code /api/status},
 * kept in memory: read when the library opens, after a dismiss, and again (on a thread of its own) when photos or the
 * re-check change it, so a poll never reads the database.
 */
public final class PlaceNotes {

    /** How many moved groups the note lists (the total counts them all). */
    public static final int GROUPS = 10;

    private static final Logger LOG = LoggerFactory.getLogger(PlaceNotes.class);

    /**
     * A place as the note shows it: {@code path} from the place up to its country; a former place's {@code name} and
     * {@code path} labelled ("Chittoor (former district)"), its level in {@code level} (5c contract §11).
     */
    public record PlaceRef(String id, String name, List<String> path, String level, boolean former) {

        public PlaceRef {
            path = List.copyOf(path);
        }
    }

    /** The changes that touch the library by kind, and {@code elsewhere}: all the others, worldwide. */
    public record Counts(int merged, int split, int retired, int renamed, int moved, int added, int elsewhere) {
    }

    /** A change that touches the library: {@code from} with the names before the step, {@code to} after it. */
    public record Item(String type, List<PlaceRef> from, List<PlaceRef> to, int photos) {
    }

    /** A user place merged into an official one. */
    public record Mine(String name, PlaceRef into, int photos, boolean intoMine) {
    }

    /** A user place left under a former parent (a retired state): it stays there, usable, with its live photos. */
    public record Kept(String name, PlaceRef under, int photos) {
    }

    /** A GPS photo the re-check left with no place: its GPS position is outside every place of the new list. */
    public record Lost(long photoId, String taxonId, String name, String fileName, PlaceRef place) {
    }

    /** Photos the re-check moved from one place to another ({@code null}: no place). */
    public record MoveGroup(PlaceRef from, PlaceRef to, int photos) {
    }

    /** {@code total} photos moved by the re-check; the first {@value #GROUPS} groups, most photos first. */
    public record Moved(int total, List<MoveGroup> groups) {
    }

    /** A species with live photos in a former place. */
    public record FormerSpecies(String id, String name, String common, PlaceRef place, int photos) {
    }

    /** Live photos in former places, and every species and place they are of (most photos first, then by name). */
    public record Former(int photos, List<FormerSpecies> species) {
    }

    /**
     * A live photo the re-check could not do (it keeps its old place): why, in plain words; {@code final} once it failed
     * as often as the app tries (it will not try again).
     */
    public record NotChecked(long photoId, String taxonId, String name, String fileName, String reason,
                             @JsonProperty("final") boolean last) {
    }

    /** The note ({@code GET /api/place-update}); {@code dismissed}: only the former and not-checked parts are shown. */
    public record Note(boolean dismissed, List<String> versions, String appliedAt, Counts counts, List<Item> items,
                       List<Mine> mine, List<Kept> kept, Moved moved, Former former, List<NotChecked> notChecked,
                       List<Lost> lost, int pending, boolean rechecking) {
    }

    /**
     * {@code /api/status}'s {@code placeUpdate} (5c contract §10, §11): {@code notChecked}, {@code pending} and
     * {@code moved} belong to the newest list's step (0 without one; {@code moved} until it is dismissed); {@code lost}
     * the GPS photos its re-check left with no place since the last Dismiss; {@code failed} the counts could not be read
     * (the last ones known are given).
     */
    public record Status(boolean note, int formerPhotos, int notChecked, int pending, int moved, int lost,
                         boolean rechecking, boolean failed) {
    }

    /** The counts behind the status, read from the database; {@code stepped}: the newest list came by a step. */
    private record Counted(boolean note, int formerPhotos, int notChecked, int pending, int moved, int lost,
                           boolean stepped, boolean rechecked) {
    }

    /** The newest list's {@code place_update} row: its version, whether its re-check is done, whether it is dismissed. */
    private record Newest(String version, boolean rechecked, boolean dismissed) {
    }

    private final Supplier<Optional<Database>> database;
    private final int placeVersion;
    private final int maxAttempts;
    private final Clock clock;
    private volatile Counted counted;
    private volatile boolean failed;
    /** This run's catch-up has gone past its places step: the re-check is not running any more in this run. */
    private volatile boolean placesFinished;
    private final AtomicBoolean recounting = new AtomicBoolean();
    private final Object counting = new Object();
    /** Guards starting a count's thread against {@link #close}. */
    private final Object starting = new Object();
    private volatile boolean closed;
    private Thread recounter;
    private volatile Runnable betweenReadAndStore = () -> { };
    private final AtomicBoolean again = new AtomicBoolean();
    /** The re-check's moves counted so far: {@code place.recheck} entries of {@link #movesOf} read up to {@link #movesUpTo}. */
    private final Object moves = new Object();
    private String movesOf;
    private long movesUpTo;
    private int moved;
    /** Photos a {@code place.recheck} of the newest list left with no place after the last Dismiss. */
    private final Set<Long> lostIds = new HashSet<>();
    /** Where each of {@link #lostIds} was before the re-check (its {@code place.recheck} entry's {@code from}). */
    private final Map<Long, String> lostFrom = new HashMap<>();

    /**
     * @param database     the open library's database, if any (nothing is read without one)
     * @param placeVersion the place consumer's version (the newest list's position)
     * @param maxAttempts  failures after which the ledger leaves a photo ({@code ProcessingLedger.MAX_ATTEMPTS})
     */
    public PlaceNotes(Supplier<Optional<Database>> database, int placeVersion, int maxAttempts, Clock clock) {
        this.database = database;
        this.placeVersion = placeVersion;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    /**
     * The app (or a test) is closing: no count starts from now on, and one under way is waited for (at most
     * {@link #CLOSE_WAIT}), so nothing reads the library after it closed.
     */
    public void close() {
        Thread running;
        synchronized (starting) {
            closed = true;
            running = recounter;
        }
        if (running != null && running != Thread.currentThread()) {
            try {
                running.join(CLOSE_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (counting) {
            // a count on another caller's thread (a note read, a Dismiss) is over once this lock is free
        }
    }

    /** The longest {@link #close} waits for a count under way. */
    public static final java.time.Duration CLOSE_WAIT = java.time.Duration.ofSeconds(10);

    /** The part of {@code /api/status}, from memory; null when there is nothing to show. */
    public Status status() {
        Counted c = counted;
        boolean f = failed;
        if (c == null) {
            if (database.get().isPresent()) {
                changed();
            }
            return f ? new Status(false, 0, 0, 0, 0, 0, false, true) : null;
        }
        boolean rechecking = c.stepped() && !c.rechecked() && !placesFinished;
        if (!c.note() && c.formerPhotos() == 0 && c.notChecked() == 0 && c.pending() == 0 && c.moved() == 0 && c.lost() == 0
                && !rechecking && !f) {
            return null;
        }
        return new Status(c.note(), c.formerPhotos(), c.notChecked(), c.pending(), c.moved(), c.lost(), rechecking, f);
    }

    /** A library was opened in this run: its counts are read now; its re-check counts as running until it is over. */
    public void opened() {
        synchronized (moves) {
            movesOf = null;
            movesUpTo = 0;
            moved = 0;
            lostIds.clear();
            lostFrom.clear();
        }
        placesFinished = false;
        recount();
    }

    /**
     * This run's catch-up has gone past its places step (the re-check, if any, is over for this run). The counts are read
     * first, on the caller's thread, so the status never says "not running" beside the counts from before it ran.
     */
    public void placesFinished() {
        recount();
        placesFinished = true;
    }

    /**
     * Photos or the re-check changed something the status counts: it is read again on a thread of its own (one at a
     * time; a change meanwhile reads it once more). Nothing is read while no library is open.
     */
    public void changed() {
        if (closed || database.get().isEmpty()) {
            return;
        }
        again.set(true);
        synchronized (starting) {
            if (closed || !recounting.compareAndSet(false, true)) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    while (!closed && again.getAndSet(false)) {
                        recount();
                    }
                } finally {
                    recounting.set(false);
                    if (again.get()) {
                        changed();
                    }
                }
            }, "atlas-place-note");
            thread.setDaemon(true);
            recounter = thread;
            thread.start();
        }
    }

    /**
     * Reads the counts (and stamps {@code rechecked_at} once no live photo is left for the re-check); a failure is logged
     * and said ({@code failed}), the last counts kept.
     */
    private void recount() {
        Optional<Database> db = database.get();
        if (db.isEmpty()) {
            return;
        }
        // One count at a time, read and stored together: a count read earlier never lands after a later one.
        synchronized (counting) {
            if (!closed) {
                store(db.get());
            }
        }
    }

    /** Runs between a count's read and its store (tests). */
    void betweenReadAndStore(Runnable hook) {
        betweenReadAndStore = hook;
    }

    private void store(Database db) {
        try {
            Counted c = db.snapshot(this::count);
            betweenReadAndStore.run();
            if (c.stepped() && !c.rechecked() && c.pending() == 0 && retryable(db) == 0) {
                markRechecked(db);
                c = new Counted(c.note(), c.formerPhotos(), c.notChecked(), c.pending(), c.moved(), c.lost(), true, true);
            }
            counted = c;
            failed = false;
        } catch (RuntimeException e) {
            failed = true;
            LOG.warn("Could not count the photos of the place update's note", e);
        }
    }

    /** The note, or null when there is nothing to show (no undismissed note, no photo in a former place, none unchecked). */
    public Note note() {
        Database db = database.get().orElseThrow(PlaceNotes::noLibrary);
        Note note = db.snapshot(this::read);
        recount();
        return note;
    }

    /** Dismiss: every step's row is stamped now (later losses and later steps come back; the former part stays). */
    public void dismiss() {
        Database db = database.get().orElseThrow(PlaceNotes::noLibrary);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE place_update SET dismissed_at = ?")) {
                ps.setString(1, clock.instant().toString());
                ps.executeUpdate();
            }
            return null;
        });
        synchronized (moves) {
            lostIds.clear();
            lostFrom.clear();
        }
        recount();
    }

    private static AtlasException noLibrary() {
        return AtlasException.noLibrary();
    }

    private record Step(String version, String appliedAt, boolean touches, PlaceStep.Summary summary) {
    }

    private static List<Step> undismissed(Connection c) throws SQLException {
        List<Step> steps = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT version, applied_at, touches, summary_json FROM place_update"
                + " WHERE dismissed_at IS NULL ORDER BY version");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                steps.add(new Step(rs.getString(1), rs.getString(2), rs.getInt(3) == 1, PlaceStep.summary(rs.getString(4))));
            }
        }
        return steps;
    }

    /** The newest list's {@code place_update} row; null when the newest list did not come by a step. */
    private static Newest newest(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT version, rechecked_at IS NOT NULL, dismissed_at IS NOT NULL"
                + " FROM place_update WHERE version = (SELECT max(bundle_version) FROM place_state)");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Newest(rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1) : null;
        }
    }

    private Counted count(Connection c) throws SQLException {
        List<Step> steps = undismissed(c);
        int formerPhotos = one(c, "SELECT COUNT(*) FROM image i JOIN place p ON p.id = i.place_id"
                + " WHERE i.deleted_at IS NULL AND p.active = 0");
        Newest newest = newest(c);
        if (newest == null) {
            return new Counted(steps.stream().anyMatch(Step::touches), formerPhotos, 0, 0, 0, 0, false, true);
        }
        int[] seen = moves(c, newest.version());
        int movedNow = newest.dismissed() ? 0 : seen[0];
        boolean note = steps.stream().anyMatch(Step::touches) || movedNow > 0;
        return new Counted(note, formerPhotos, count(c, NOT_CHECKED) + count(c, NO_DETAILS), count(c, PENDING), movedNow,
                seen[1], true,
                newest.rechecked());
    }

    /**
     * The re-check's moves of {@code version} and how many photos it left with no place are still so, counted in memory:
     * only the {@code place.recheck} entries written since the last read are read (by the {@code change_log_kind} index
     * and the id). Returns {moved, lost}.
     */
    private int[] moves(Connection c, String version) throws SQLException {
        synchronized (moves) {
            if (!version.equals(movesOf)) {
                movesOf = version;
                movesUpTo = 0;
                moved = 0;
                lostIds.clear();
                lostFrom.clear();
            }
            String dismissed = one(c, "SELECT max(dismissed_at) FROM place_update", String.class);
            try (PreparedStatement ps = c.prepareStatement("SELECT id, at, json_extract(payload_json, '$.photoId'),"
                    + " json_extract(payload_json, '$.to') IS NULL, json_extract(payload_json, '$.version') = ?,"
                    + " json_extract(payload_json, '$.from') FROM change_log WHERE kind = ? AND id > ? ORDER BY id")) {
                ps.setString(1, version);
                ps.setString(2, ChangeLog.PLACE_RECHECK);
                ps.setLong(3, movesUpTo);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        movesUpTo = rs.getLong(1);
                        if (rs.getInt(5) != 1) {
                            continue;
                        }
                        if (rs.getInt(4) != 1) {
                            moved++; // a photo left with no place is lost, not moved
                        } else if (dismissed == null || rs.getString(2).compareTo(dismissed) > 0) {
                            lostIds.add(rs.getLong(3));
                            lostFrom.put(rs.getLong(3), rs.getString(6));
                        }
                    }
                }
            }
            return new int[] {moved, placeless(c, lostIds).size()};
        }
    }

    /** Those of these photos that are live and still have no place the user did not choose to remove, by id. */
    private static List<Long> placeless(Connection c, Set<Long> ids) throws SQLException {
        List<Long> left = new ArrayList<>();
        for (List<Long> chunk : SqlLists.chunks(new ArrayList<>(ids))) {
            // A photo whose place the user removed (place_source 'user') is the user's choice, not lost.
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM image WHERE deleted_at IS NULL AND place_id IS NULL"
                    + " AND (place_source IS NULL OR place_source <> 'user') AND id IN (" + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        left.add(rs.getLong(1));
                    }
                }
            }
        }
        left.sort(null);
        return left;
    }

    /** Live photos the ledger would still give the re-check: failed at the current version fewer than the most times. */
    private int retryable(Database db) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*)" + NOT_CHECKED + " AND p.attempts < ?")) {
                ps.setInt(1, placeVersion);
                ps.setInt(2, maxAttempts);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    private void markRechecked(Database db) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE place_update SET rechecked_at = ? WHERE rechecked_at IS NULL"
                    + " AND version = (SELECT max(bundle_version) FROM place_state)")) {
                ps.setString(1, clock.instant().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private Note read(Connection c) throws SQLException {
        Counted count = count(c);
        List<Step> steps = undismissed(c);
        if (!count.note() && count.formerPhotos() == 0 && count.notChecked() == 0 && count.lost() == 0) {
            return null;
        }
        boolean dismissed = !count.note();
        List<Step> shown = dismissed ? List.of() : steps;
        // Every place the note names, read once.
        Set<String> ids = new HashSet<>();
        for (Step step : shown) {
            step.summary().items().forEach(i -> {
                ids.addAll(i.from());
                ids.addAll(i.to());
            });
            step.summary().mine().forEach(m -> ids.add(m.into()));
        }
        List<String[]> groups = new ArrayList<>();
        int total = 0;
        for (Step step : shown) {
            for (String[] group : moveGroups(c, step.version())) {
                groups.add(group);
                total += Integer.parseInt(group[2]);
                ids.add(group[0]);
                ids.add(group[1]);
            }
        }
        List<String[]> former = formerRows(c);
        former.forEach(r -> ids.add(r[1]));
        List<String[]> kept = keptRows(c);
        kept.forEach(r -> ids.add(r[1]));
        List<String[]> lost = count.stepped() ? lostRows(c) : List.of();
        lost.forEach(r -> ids.add(r[4]));
        ids.remove(null);
        Map<String, PlaceSearch.Place> places = PlaceRules.places(c, ids);

        int[] touching = new int[PlaceChange.TYPES.size()];
        int all = 0;
        List<Item> items = new ArrayList<>();
        List<Mine> mine = new ArrayList<>();
        for (Step step : shown) {
            PlaceStep.Counts n = step.summary().counts();
            all += n.merged() + n.split() + n.retired() + n.renamed() + n.moved() + n.added();
            for (PlaceStep.Item item : step.summary().items()) {
                int kind = PlaceChange.TYPES.indexOf(item.type());
                touching[kind] += item.type().equals(PlaceChange.SPLIT) || item.type().equals(PlaceChange.RETIRE) ? 1
                        : item.from().size();
                List<PlaceRef> from = new ArrayList<>();
                for (int i = 0; i < item.from().size(); i++) {
                    from.add(before(places.get(item.from().get(i)), item.from().get(i), item.names().from().get(i)));
                }
                items.add(new Item(item.type(), from, item.to().stream().map(id -> ref(places.get(id), id)).toList(),
                        item.photos()));
            }
            for (PlaceStep.Mine m : step.summary().mine()) {
                mine.add(new Mine(m.name(), ref(places.get(m.into()), m.into()), m.photos(), m.intoMine()));
            }
        }
        items.sort(Comparator.comparingInt(Item::photos).reversed());
        int touched = 0;
        for (int t : touching) {
            touched += t;
        }
        Counts counts = new Counts(touching[0], touching[1], touching[2], touching[3], touching[4], touching[5],
                Math.max(0, all - touched));
        groups.sort(Comparator.comparingInt((String[] g) -> -Integer.parseInt(g[2])));
        List<MoveGroup> moveGroups = groups.stream().limit(GROUPS).map(g -> new MoveGroup(
                g[0] == null ? null : ref(places.get(g[0]), g[0]), g[1] == null ? null : ref(places.get(g[1]), g[1]),
                Integer.parseInt(g[2]))).toList();
        List<FormerSpecies> species = new ArrayList<>();
        int formerPhotos = 0;
        for (String[] r : former) {
            int photos = Integer.parseInt(r[4]);
            formerPhotos += photos;
            species.add(new FormerSpecies(r[0], r[2], r[3], ref(places.get(r[1]), r[1]), photos));
        }
        species.sort(Comparator.comparingInt(FormerSpecies::photos).reversed().thenComparing(FormerSpecies::name)
                .thenComparing(s -> s.place().name()));
        String appliedAt = shown.isEmpty() ? null : shown.getLast().appliedAt();
        return new Note(dismissed, shown.stream().map(Step::version).toList(), appliedAt, counts, items, mine,
                kept.stream().map(r -> new Kept(r[0], ref(places.get(r[1]), r[1]), Integer.parseInt(r[2]))).toList(),
                new Moved(total, moveGroups), new Former(formerPhotos, species), count.stepped() ? notChecked(c) : List.of(),
                lost.stream().map(r -> new Lost(Long.parseLong(r[0]), r[1], r[2], r[3], r[4] == null ? null
                        : ref(places.get(r[4]), r[4]))).toList(),
                count.pending(), count.stepped() && !count.rechecked() && !placesFinished);
    }

    /** What the note calls a place the library no longer has (its {@code level} null says so). */
    static final String NO_LONGER_EXISTS = "a place that no longer exists";

    private static PlaceRef ref(PlaceSearch.Place place, String id) {
        return place == null ? new PlaceRef(id, NO_LONGER_EXISTS, List.of(NO_LONGER_EXISTS), null, false)
                : new PlaceRef(place.id(), place.name(), place.path(), place.level(), place.former());
    }

    /**
     * A place as it was named before the step: that name first (labelled when the place is former now, as every
     * PlaceRef's name is), then its places above as they are now.
     */
    private static PlaceRef before(PlaceSearch.Place place, String id, String name) {
        PlaceRef now = ref(place, id);
        String shown = now.level() == null ? name : PlaceStore.label(name, now.level(), !now.former(), now.path().getLast());
        List<String> path = new ArrayList<>(now.path());
        path.set(0, shown);
        return new PlaceRef(now.id(), shown, path, now.level(), now.former());
    }

    /** The re-check's moves of a step's version into a place (not those left with none: lost): {from, to, photos}. */
    private static List<String[]> moveGroups(Connection c, String version) throws SQLException {
        List<String[]> groups = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT json_extract(payload_json, '$.from'),"
                + " json_extract(payload_json, '$.to'), COUNT(*) FROM change_log WHERE kind = ?"
                + " AND json_extract(payload_json, '$.version') = ? AND json_extract(payload_json, '$.to') IS NOT NULL"
                + " GROUP BY 1, 2")) {
            ps.setString(1, ChangeLog.PLACE_RECHECK);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(new String[] {rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))});
                }
            }
        }
        return groups;
    }

    /** Live photos in inactive places: {taxon id, place id, name, common name, photos}. */
    private static List<String[]> formerRows(Connection c) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.taxon_id, i.place_id, t.name, t.common_name, COUNT(*)"
                + " FROM image i JOIN place p ON p.id = i.place_id JOIN taxon t ON t.id = i.taxon_id"
                + " WHERE i.deleted_at IS NULL AND p.active = 0 GROUP BY i.taxon_id, i.place_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        String.valueOf(rs.getInt(5))});
            }
        }
        return rows;
    }

    private static final String NOT_CHECKED = " FROM image i JOIN image_processing p ON p.image_id = i.id"
            + " AND p.consumer = 'place' AND p.version = ? AND p.status = 'failed' WHERE i.deleted_at IS NULL";

    /**
     * The details step's ledger key and version: {@code photos.ImageMeta.DETAILS} and {@code DETAILS_VERSION}, copied
     * because photos reach this package through library (a dependency would close a cycle); a test pins them equal.
     */
    static final String DETAILS_KEY = "details";
    static final int DETAILS_VERSION = 3;
    private static final String DETAILS = "'" + DETAILS_KEY + "'";
    /** The details step has failed for the photo (its details couldn't be read). */
    private static final String DETAILS_FAILED = "EXISTS (SELECT 1 FROM image_processing d WHERE d.image_id = i.id"
            + " AND d.consumer = " + DETAILS + " AND d.version = " + DETAILS_VERSION + " AND d.status = 'failed')";
    /**
     * Live photos with no ledger row at the place consumer's version: not checked yet, the catch-up will (from their
     * details, or once its details step has read them). A photo whose details step failed is {@link #NO_DETAILS}.
     */
    private static final String PENDING = " FROM image i WHERE i.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM"
            + " image_processing p WHERE p.image_id = i.id AND p.consumer = 'place' AND p.version = ?)"
            + " AND (EXISTS (SELECT 1 FROM image_meta m WHERE m.image_id = i.id) OR NOT " + DETAILS_FAILED + ")";
    /** Live photos not checked at the place consumer's version because their details couldn't be read. */
    private static final String NO_DETAILS = " FROM image i WHERE i.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM"
            + " image_processing p WHERE p.image_id = i.id AND p.consumer = 'place' AND p.version = ?)"
            + " AND NOT EXISTS (SELECT 1 FROM image_meta m WHERE m.image_id = i.id) AND " + DETAILS_FAILED;

    private int count(Connection c, String from) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*)" + from)) {
            ps.setInt(1, placeVersion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<NotChecked> notChecked(Connection c) throws SQLException {
        List<NotChecked> photos = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.taxon_id, t.name, i.file_name, p.error, p.attempts"
                + NOT_CHECKED.replace(" WHERE", " JOIN taxon t ON t.id = i.taxon_id WHERE") + " ORDER BY i.id")) {
            ps.setInt(1, placeVersion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photos.add(new NotChecked(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            reason(rs.getString(5)), rs.getInt(6) >= maxAttempts));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.taxon_id, t.name, i.file_name, coalesce((SELECT"
                + " d.attempts FROM image_processing d WHERE d.image_id = i.id AND d.consumer = " + DETAILS
                + " AND d.version = " + DETAILS_VERSION + " AND d.status = 'failed'), 0)"
                + NO_DETAILS.replaceFirst(" WHERE", " JOIN taxon t ON t.id = i.taxon_id WHERE") + " ORDER BY i.id")) {
            ps.setInt(1, placeVersion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photos.add(new NotChecked(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            "Its details couldn't be read.", rs.getInt(5) >= maxAttempts));
                }
            }
        }
        photos.sort(java.util.Comparator.comparingLong(NotChecked::photoId));
        return photos;
    }

    /**
     * Why the re-check could not do a photo, in plain words, from the ledger's error (which is never shown as it is: it
     * holds an exception's class and message).
     */
    static String reason(String error) {
        String e = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("full")) {
            return "The drive with your library was full.";
        }
        if (e.contains("lock") || e.contains("busy")) {
            return "Another program was using your library.";
        }
        if (e.contains("i/o") || e.contains("ioerr") || e.contains("disk") || e.contains("readonly")) {
            return "Your library couldn't be read or written.";
        }
        if (e.contains("boundaries")) {
            return "The place boundaries couldn't be read.";
        }
        return "Something unexpected stopped it.";
    }

    /** User places under an inactive parent: {name, parent id, live photos}. */
    private static List<String[]> keptRows(Connection c) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT u.name, u.parent_id, (SELECT COUNT(*) FROM image i"
                + " WHERE i.place_id = u.id AND i.deleted_at IS NULL) FROM place u JOIN place p ON p.id = u.parent_id"
                + " WHERE u.source = 'user' AND p.active = 0 ORDER BY u.sort_key, u.id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))});
            }
        }
        return rows;
    }

    /**
     * The photos the newest list's re-check left with no place since the last Dismiss and still without one: {photo
     * id, taxon id, species name, file name, the place it was in}.
     */
    private List<String[]> lostRows(Connection c) throws SQLException {
        List<Long> ids;
        Map<Long, String> from;
        synchronized (moves) {
            ids = placeless(c, lostIds);
            from = new HashMap<>(lostFrom);
        }
        List<String[]> rows = new ArrayList<>();
        for (List<Long> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.taxon_id, t.name, i.file_name FROM image i"
                    + " JOIN taxon t ON t.id = i.taxon_id WHERE i.id IN (" + SqlLists.marks(chunk.size()) + ") ORDER BY i.id")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3),
                                rs.getString(4), from.get(rs.getLong(1))});
                    }
                }
            }
        }
        return rows;
    }

    private static int one(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String one(Connection c, String sql, Class<String> type) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return type.cast(rs.getString(1));
        }
    }
}
