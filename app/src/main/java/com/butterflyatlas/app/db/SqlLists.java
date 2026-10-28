package com.butterflyatlas.app.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@code IN (?, …)} lists: values split into statement-sized chunks, their question marks, and binding them. */
public final class SqlLists {

    /** The most values one {@code IN} list takes (well under SQLite's 32,766 parameters per statement). */
    public static final int CHUNK = 500;

    private SqlLists() {
    }

    /** {@code values} in order, {@value #CHUNK} at a time (views of the list); none for an empty list. */
    public static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < values.size(); from += CHUNK) {
            chunks.add(values.subList(from, Math.min(values.size(), from + CHUNK)));
        }
        return chunks;
    }

    /** {@code ?, ?, ?} for {@code n} values. */
    public static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    /** Binds {@code values} (longs or strings) from position {@code at}; returns the next position. */
    public static int bind(PreparedStatement ps, int at, List<?> values) throws SQLException {
        for (Object value : values) {
            if (value instanceof Long l) {
                ps.setLong(at++, l);
            } else if (value instanceof String s) {
                ps.setString(at++, s);
            } else {
                throw new IllegalArgumentException("An IN list takes longs or strings, not " + value);
            }
        }
        return at;
    }
}
