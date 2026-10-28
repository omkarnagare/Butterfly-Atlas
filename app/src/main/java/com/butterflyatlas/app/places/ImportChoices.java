package com.butterflyatlas.app.places;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * What import items that chose a user place hear when it is renamed or deleted, inside the caller's transaction
 * (implemented by the imports, so places need not know them).
 */
public interface ImportChoices {

    /** Items that chose {@code placeId} (just renamed) are stamped so their import's grid shows the new name. */
    void renamed(Connection c, String placeId, Instant now) throws SQLException;

    /**
     * Items that chose {@code placeId} (being deleted) lose the choice and show their GPS place again. What it returns is
     * told once the caller's transaction is over, committed or not.
     */
    Deleted deleted(Connection c, String placeId, Instant now) throws SQLException;

    /** A delete's effect on the imports, settled when its transaction is over. */
    @FunctionalInterface
    interface Deleted {

        Deleted NONE = committed -> { };

        void over(boolean committed);
    }
}
