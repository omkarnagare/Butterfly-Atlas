package com.butterflyatlas.importer.bundle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Locale;

/**
 * One difference from the previous bundle, in Atlas ids.
 *
 * @param from   affected ids in the previous bundle (empty for ADD)
 * @param to     ids in this bundle (empty for REMOVE)
 * @param detail human-readable summary, e.g. "Euploea core → Euploea coreta"
 */
@JsonPropertyOrder({"type", "from", "to", "detail"})
public record Change(Type type, List<String> from, List<String> to, String detail) {

    public Change {
        from = from == null ? List.of() : from;
        to = to == null ? List.of() : to;
    }

    public enum Type {
        ADD, REMOVE, RENAME, MOVE, MERGE, SPLIT;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Type fromJson(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
