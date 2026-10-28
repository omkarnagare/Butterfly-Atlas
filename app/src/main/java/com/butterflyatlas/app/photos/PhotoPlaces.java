package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.UserPlaces;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Setting and removing the place of photos as the user's (3c-2 contract §1 "Photos"; design C10). */
public final class PhotoPlaces {

    private final Supplier<Library> library;
    private final Clock clock;
    private final List<Runnable> changed = new CopyOnWriteArrayList<>();

    public PhotoPlaces(Supplier<Library> library, Clock clock) {
        this.library = library;
        this.clock = clock;
    }

    /** Runs {@code listener} after photos' places were set or removed (committed). Keep it quick. */
    public void onChange(Runnable listener) {
        changed.add(listener);
    }

    /**
     * Sets {@code placeId} on live photos, or removes their place ({@code remove}), as the user's ({@code place_source
     * 'user'}, GPS or not), all in one transaction: an unknown photo, one in Recently deleted, or an unknown or retired
     * place changes nothing. Each photo's change is recorded in {@code change_log} with the place and source it had (a
     * photo that already has this place as the user's is not changed); when any photo changed, the place becomes the
     * newest recent place. Returns the photos in the order given (each once).
     */
    public List<Photo> place(List<Long> rawIds, String placeId, boolean remove) {
        List<Long> ids = PlaceRules.photoIds(rawIds);
        if ((placeId == null) != remove) {
            throw PlaceRules.invalid("Choose a place, or remove the place.");
        }
        List<Photo> placed = library.get().database().write(c -> {
            PhotoQueries.live(c, ids);   // every photo must be live before anything changes
            if (placeId != null) {
                PlaceRules.usable(c, placeId);
            }
            Map<Long, String[]> before = new HashMap<>();   // the place_id and place_source each photo had
            for (List<Long> chunk : SqlLists.chunks(ids)) {
                try (PreparedStatement ps = c.prepareStatement("SELECT id, place_id, place_source FROM image WHERE id IN ("
                        + SqlLists.marks(chunk.size()) + ")")) {
                    SqlLists.bind(ps, 1, chunk);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            before.put(rs.getLong(1), new String[] {rs.getString(2), rs.getString(3)});
                        }
                    }
                }
            }
            // A photo that already has this place as the user's is left alone: no change_log row for it.
            List<Long> changed = ids.stream().filter(id -> !(Objects.equals(before.get(id)[0], placeId)
                    && PlaceRules.USER.equals(before.get(id)[1]))).toList();
            Instant now = clock.instant();
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET place_id = ?, place_source = 'user' WHERE id = ?")) {
                for (long id : changed) {
                    ps.setString(1, placeId);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            ChangeLog.recordAll(c, now, ChangeLog.PLACE, changed.stream().map(id -> ChangeLog.payload("id", id,
                    "placeId", placeId, "fromPlaceId", before.get(id)[0], "fromSource", before.get(id)[1])).toList());
            if (placeId != null && !changed.isEmpty()) {
                UserPlaces.used(c, placeId, now);
            }
            return PhotoQueries.live(c, ids);
        });
        changed.forEach(Runnable::run);
        return placed;
    }
}
