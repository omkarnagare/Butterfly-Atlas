package com.butterflyatlas.app.updates;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The library as an update step sees it: its taxa ({@link Tree}), its live photos and its photos in Recently deleted.
 * The summary's dry run takes a copy through every step of the chain without writing anything.
 */
record Model(Tree tree, List<Live> live, List<Deleted> deleted) {

    /** A live photo: its species, where its file is, its name and number (null for a file named by hand). */
    record Live(long id, String taxonId, String relPath, String fileName, Integer number) {
    }

    /** A photo in Recently deleted: its species and the name (and number) it is restored under. */
    record Deleted(long id, String taxonId, String fileName, Integer number) {
    }

    static Model load(Connection c) throws SQLException {
        return new Model(Tree.load(c), live(c), deleted(c));
    }

    static List<Live> live(Connection c) throws SQLException {
        List<Live> live = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, taxon_id, rel_path, file_name, number FROM image"
                + " WHERE deleted_at IS NULL ORDER BY id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                live.add(new Live(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), number(rs, 5)));
            }
        }
        return live;
    }

    static List<Deleted> deleted(Connection c) throws SQLException {
        List<Deleted> deleted = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, taxon_id, file_name, number FROM image"
                + " WHERE deleted_at IS NOT NULL ORDER BY id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                deleted.add(new Deleted(rs.getLong(1), rs.getString(2), rs.getString(3), number(rs, 4)));
            }
        }
        return deleted;
    }

    /** An integer column, null when SQL NULL. */
    static Integer number(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
