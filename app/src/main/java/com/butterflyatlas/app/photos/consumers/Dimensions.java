package com.butterflyatlas.app.photos.consumers;

import com.butterflyatlas.app.photos.PhotoConsumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Displayed width and height and the EXIF orientation of each photo, on {@code image}. */
public final class Dimensions implements PhotoConsumer {

    public static final String KEY = "dimensions";
    public static final int VERSION = 1;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Kind kind() {
        return Kind.FACTS;
    }

    @Override
    public void apply(List<Subject> batch, Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE image SET width = ?, height = ?, orientation = ? WHERE id = ?")) {
            for (Subject subject : batch) {
                setIntOrNull(ps, 1, subject.facts().width());
                setIntOrNull(ps, 2, subject.facts().height());
                ps.setInt(3, subject.facts().orientation());
                ps.setLong(4, subject.imageId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void setIntOrNull(PreparedStatement ps, int at, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(at, Types.INTEGER);
        } else {
            ps.setInt(at, value);
        }
    }
}
