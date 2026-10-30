package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings a library's places up to the newest shipped place list when it opens (5c contract §4), before anything reads
 * places: the library's version is that of its {@code place_state} row furthest along the chain. A new library loads the
 * newest list ({@link PlaceStore#syncBundle}); one on the newest list only refreshes names when the list's checksum
 * differs; one on an older list takes every later list as one step, oldest first, each in its own write transaction
 * ({@link PlaceStep}), so a crash inside a step leaves it undone and the next opening applies it again, and a crash after
 * it goes on from the next. A library on a list this app does not ship is never guessed at: it is refused.
 */
public final class PlaceChain {

    private PlaceChain() {
    }

    /**
     * Why a library whose places were loaded from {@code recorded} (its {@code place_state} versions, oldest first) can't
     * be opened by this app, in plain words; empty when every list is shipped.
     */
    public static java.util.Optional<String> unavailable(List<String> recorded, PlaceBundles bundles) {
        String unknown = null;
        for (String version : recorded) {
            if (bundles.position(version) < 0) {
                unknown = version; // versions are dates: the last one is the newest
            }
        }
        if (unknown == null) {
            return java.util.Optional.empty();
        }
        // Versions are dates: after the newest shipped means a newer app made it; otherwise this copy lacks it.
        if (unknown.compareTo(bundles.version()) > 0) {
            return java.util.Optional.of("This library uses place data from a newer version of Butterfly Atlas (" + unknown
                    + "). Install that version or a newer one to open it.");
        }
        return java.util.Optional.of("This library uses place data (" + unknown + ") that this copy of Butterfly Atlas"
                + " doesn't have. Open it with the copy of Butterfly Atlas it was last used with, or a newer one.");
    }

    /**
     * Brings the library's places up to date; returns the versions of the steps applied now, oldest first (empty when
     * none). A step that fails throws {@link PlaceUpdateException} with the earlier steps kept; a library on a list not
     * shipped throws {@link PlaceDataUnavailableException}.
     */
    public static List<String> bringUpToDate(Database database, PlaceBundles bundles, Clock clock) {
        List<String> recorded = database.read(c -> {
            List<String> versions = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT bundle_version FROM place_state ORDER BY 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.add(rs.getString(1));
                }
            }
            return versions;
        });
        java.util.Optional<String> missing = unavailable(recorded, bundles);
        if (missing.isPresent()) {
            throw new PlaceDataUnavailableException(missing.get());
        }
        int at = 0;
        for (String version : recorded) {
            at = Math.max(at, bundles.position(version));
        }
        PlaceStore store = new PlaceStore(clock);
        if (at == 0 || at == bundles.all().size()) {
            // The names earlier steps carried stay when the newest list is loaded again (another checksum).
            database.write(c -> store.syncBundle(c, bundles, PlaceStep.kept(c)));
            return List.of();
        }
        List<String> applied = new ArrayList<>();
        PlaceStep step = new PlaceStep(clock);
        for (int i = at; i < bundles.all().size(); i++) {
            PlaceBundles.Shipped from = bundles.all().get(i - 1);
            PlaceBundles.Shipped to = bundles.all().get(i);
            try {
                database.write(c -> step.apply(c, from, to));
            } catch (RuntimeException e) {
                throw new PlaceUpdateException(from.version(), to.version(), applied, e);
            }
            applied.add(to.version());
        }
        return List.copyOf(applied);
    }
}
