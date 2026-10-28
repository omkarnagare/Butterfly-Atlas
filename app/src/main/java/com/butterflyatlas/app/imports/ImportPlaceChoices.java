package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.OpenImports;
import com.butterflyatlas.app.places.ImportChoices;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Import items that chose a user place follow it when it is renamed or deleted (only open imports are stamped). */
public final class ImportPlaceChoices implements ImportChoices {

    /** The open library, whose imports' revisions go up when a place they chose is deleted (null: none to tell). */
    private final Supplier<Library> library;

    /** Telling no import's revision (tests of places alone). */
    public ImportPlaceChoices() {
        this(null);
    }

    public ImportPlaceChoices(Supplier<Library> library) {
        this.library = library;
    }

    /**
     * Items whose chosen place is {@code placeId} (a user place just renamed) take a change number of their import, so
     * its grid hears of the new name; a finished import is left alone.
     */
    @Override
    public void renamed(Connection c, String placeId, Instant now) throws SQLException {
        for (long runId : runsChoosing(c, placeId)) {
            long seq = ImportStore.stamp(c, runId, now);
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET seq = ? WHERE run_id = ?"
                    + " AND user_place_set = 1 AND user_place_id = ?")) {
                ps.setLong(1, seq);
                ps.setLong(2, runId);
                ps.setString(3, placeId);
                ps.executeUpdate();
            }
        }
    }

    private static List<Long> runsChoosing(Connection c, String placeId) throws SQLException {
        List<Long> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT i.run_id FROM import_item i JOIN import_run r"
                + " ON r.id = i.run_id WHERE r.state = 'open' AND i.user_place_set = 1 AND i.user_place_id = ? ORDER BY i.run_id")) {
            ps.setString(1, placeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(rs.getLong(1));
                }
            }
        }
        return runs;
    }

    /**
     * Items whose chosen place is {@code placeId} (a user place being deleted) lose the choice and show their GPS place
     * again; each open import they belong to takes a change number, so its grid hears of it (a finished import's items
     * forget it unstamped).
     */
    @Override
    public Deleted deleted(Connection c, String placeId, Instant now) throws SQLException {
        List<Long> runs = runsChoosing(c, placeId);
        Deleted over = Deleted.NONE;
        if (library != null) {
            OpenImports open = library.get().openImports();
            open.refuseWhileFiling(runs, OpenImports.PLACE_FILING); // the caller's write rolls back
            OpenImports.Change change = open.change();
            change.add(runs); // their revisions go up once the caller's write has committed (multi-window contract §5)
            over = committed -> {
                if (committed) {
                    change.committed();
                }
                change.close();
            };
        }
        for (long runId : runs) {
            long seq = ImportStore.stamp(c, runId, now);
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET user_place_set = 0, user_place_id = NULL,"
                    + " seq = ? WHERE run_id = ? AND user_place_set = 1 AND user_place_id = ?")) {
                ps.setLong(1, seq);
                ps.setLong(2, runId);
                ps.setString(3, placeId);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET user_place_set = 0, user_place_id = NULL"
                + " WHERE user_place_set = 1 AND user_place_id = ?")) {
            ps.setString(1, placeId);
            ps.executeUpdate();
        }
        return over;
    }
}
