package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.library.Library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.function.Supplier;

/** A species' cover photo: set by the user, cleared when that photo leaves the species. */
final class Covers {

    private final Supplier<Library> library;
    private final Clock clock;

    Covers(Supplier<Library> library, Clock clock) {
        this.library = library;
        this.clock = clock;
    }

    /** Sets a species' cover ({@code photoId} one of its live photos), or clears it with null. */
    Long setCover(String taxonId, Long photoId) {
        return library.get().database().write(c -> {
            PhotoRows.species(c, taxonId);
            if (photoId != null && !PhotoRows.live(c, photoId).taxonId().equals(taxonId)) {
                throw AtlasException.invalid("That photo is not of this species.");
            }
            Long before = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT cover_image_id FROM taxon WHERE id = ?")) {
                ps.setString(1, taxonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long value = rs.getLong(1);
                        before = rs.wasNull() ? null : value;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET cover_image_id = ? WHERE id = ?")) {
                ps.setObject(1, photoId);
                ps.setString(2, taxonId);
                ps.executeUpdate();
            }
            ChangeLog.record(c, clock.instant(), ChangeLog.COVER, ChangeLog.payload("taxonId", taxonId,
                    "from", before, "to", photoId));
            return photoId;
        });
    }

    /** A species whose cover is this photo goes back to the automatic cover (inside the caller's transaction). */
    static void clearCover(Connection c, long imageId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET cover_image_id = NULL WHERE cover_image_id = ?")) {
            ps.setLong(1, imageId);
            ps.executeUpdate();
        }
    }
}
