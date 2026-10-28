package com.butterflyatlas.app.places;

import java.util.List;
import java.util.Map;

/**
 * One place of the bundled place list (contract §2): {@code parentId} null for a country; {@code level} country, state
 * or district; {@code aliases} other spellings (never the name itself); {@code codes} by kind ({@code iso}, {@code lgd},
 * {@code gb}, {@code ne}), any of which may be absent.
 */
public record PlaceDef(String id, String parentId, String level, String name, List<String> aliases,
                       Map<String, String> codes) {

    public PlaceDef {
        aliases = List.copyOf(aliases);
        codes = Map.copyOf(codes);
    }
}
