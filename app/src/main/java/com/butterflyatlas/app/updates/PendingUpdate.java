package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.updates.UpdateViews.Summary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the library has a taxonomy update waiting (5b contract §2): the shipped bundles after the library's version,
 * following {@code basedOn} from it to the newest. None: no update; the library's version not shipped: unavailable (the
 * library keeps working as it is). {@link #summary} is the dry run over the whole chain.
 */
public final class PendingUpdate {

    public enum State { NONE, UNAVAILABLE, PENDING }

    public static final String UNAVAILABLE = "unavailable";

    private final State state;
    private final String from;
    private final List<TaxonomyBundles.Shipped> steps;

    private PendingUpdate(State state, String from, List<TaxonomyBundles.Shipped> steps) {
        this.state = state;
        this.from = from;
        this.steps = List.copyOf(steps);
    }

    /** The library's version: the {@code taxonomy_state} row with the highest {@code bundle_version}. */
    public static String libraryVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT max(bundle_version) FROM taxonomy_state");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    public static PendingUpdate of(Database db, TaxonomyBundles bundles) {
        return of(db.read(PendingUpdate::libraryVersion), bundles);
    }

    /** The bundles that follow {@code version}, oldest first. */
    static PendingUpdate of(String version, TaxonomyBundles bundles) {
        if (version == null || bundles.byVersion(version).isEmpty()) {
            return new PendingUpdate(State.UNAVAILABLE, version, List.of());
        }
        List<TaxonomyBundles.Shipped> chain = new ArrayList<>();
        String at = version;
        while (true) {
            String current = at;
            Optional<TaxonomyBundles.Shipped> next = bundles.all().stream()
                    .filter(s -> Objects.equals(s.bundle().basedOn(), current)).findFirst();
            if (next.isEmpty()) {
                break;
            }
            chain.add(next.get());
            at = next.get().bundle().version();
        }
        return new PendingUpdate(chain.isEmpty() ? State.NONE : State.PENDING, version, chain);
    }

    public State state() {
        return state;
    }

    /** The library's version. */
    public String from() {
        return from;
    }

    /** The newest bundle (the library's version when there is nothing to apply). */
    public String to() {
        return steps.isEmpty() ? from : steps.getLast().bundle().version();
    }

    /** The bundles to apply, oldest first. */
    public List<TaxonomyBundles.Shipped> steps() {
        return steps;
    }

    public List<String> versions() {
        return steps.stream().map(s -> s.bundle().version()).toList();
    }

    /**
     * Why the library's taxonomy cannot be updated, what is safe, and what to do (5b contract §7); null unless
     * unavailable.
     */
    public String notice() {
        if (state == State.UNAVAILABLE && from == null) {
            return "This library doesn't record which species list it uses, so it can't be updated. Your photos and folders"
                    + " are safe and everything else works as usual.";
        }
        return state == State.UNAVAILABLE ? "This library was last used with a newer version of Butterfly Atlas (its species"
                + " list is from " + from + "). Your photos and folders are safe and everything else works as usual. Install"
                + " that version or a newer one to keep the species list up to date." : null;
    }

    /** The dry run over the whole chain, from one state of the library (nothing written); null unless pending. */
    public Summary summary(Database db) {
        if (state != State.PENDING) {
            return null;
        }
        List<Bundle> chain = steps.stream().map(TaxonomyBundles.Shipped::bundle).toList();
        return db.snapshot(c -> DryRun.summary(Model.load(c), chain));
    }
}
