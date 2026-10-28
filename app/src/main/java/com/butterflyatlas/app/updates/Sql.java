package com.butterflyatlas.app.updates;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Binding of the update's nullable integers. */
final class Sql {

    private Sql() {
    }

    /** An integer, or SQL NULL for null. */
    static void setInteger(PreparedStatement ps, int at, Number value) throws SQLException {
        if (value == null) {
            ps.setNull(at, Types.INTEGER);
        } else {
            ps.setLong(at, value.longValue());
        }
    }
}
