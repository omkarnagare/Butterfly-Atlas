package com.butterflyatlas.app.places;

import java.util.List;

/**
 * Where a photo (or an import item) was observed, as the UI gets it (contract §1): {@code path} runs from the place
 * itself up to its country (1–3 names); {@code ids} are the ids of those places in the same order (4a contract §3);
 * {@code source} is {@code "gps"} (looked up from the photo's GPS) or {@code "user"} (set by the user); {@code former}
 * whether the place is no longer active (5c contract §6: its name in {@code path} is labelled "(former district)");
 * {@code level} the place's own level (country, state or district).
 */
public record PlaceRef(String id, List<String> path, List<String> ids, String source, boolean former, String level) {

    public PlaceRef {
        path = List.copyOf(path);
        ids = List.copyOf(ids);
    }

    /** The place of this view, from {@code source}. */
    public static PlaceRef of(PlaceStore.PlaceView view, String source) {
        return new PlaceRef(view.id(), view.path(), view.ids(), source, view.former(), view.level());
    }
}
