package com.butterflyatlas.app.places;

import java.util.List;

/**
 * One entry of a place list's {@code changes} (5c contract §2), in place ids: {@code merge} (one or more {@code from}
 * gone, one {@code to}), {@code split} (one {@code from}, two or more {@code to}, which may keep the {@code from}),
 * {@code retire} (one {@code from} gone, no {@code to}); and, for the reader, {@code rename}, {@code move} ({@code from}
 * and {@code to} the place) and {@code add} ({@code to} the places). {@code detail} says it in words.
 */
public record PlaceChange(String type, List<String> from, List<String> to, String detail) {

    public static final String MERGE = "merge";
    public static final String SPLIT = "split";
    public static final String RETIRE = "retire";
    public static final String RENAME = "rename";
    public static final String MOVE = "move";
    public static final String ADD = "add";
    /** In the order the importer writes them. */
    public static final List<String> TYPES = List.of(MERGE, SPLIT, RETIRE, RENAME, MOVE, ADD);

    public PlaceChange {
        from = List.copyOf(from);
        to = List.copyOf(to);
    }
}
