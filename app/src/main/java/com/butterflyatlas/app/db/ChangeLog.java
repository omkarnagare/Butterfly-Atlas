package com.butterflyatlas.app.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rows in {@code change_log} for every change to photo files, covers and places (design P7). */
public final class ChangeLog {

    public static final String MOVE = "photo.move";
    public static final String DELETE = "photo.delete";
    public static final String RESTORE = "photo.restore";
    public static final String PURGE = "photo.purge";
    public static final String MISSING = "photo.missing";
    /** A photo file found under another name or folder (renamed by hand): its record follows it. */
    public static final String RENAME = "photo.rename";
    public static final String COVER = "cover.set";
    /** A photo's place set or removed by the user (with the place and source it had). */
    public static final String PLACE = "photo.place";
    /** A GPS photo's place changed by the re-check after a place update (5c contract §5): from, to, the list's version. */
    public static final String PLACE_RECHECK = "place.recheck";
    /** A user place merged into another place by a place update step (design P4): userId, name, into, photos. */
    public static final String USER_PLACE_MERGED = "place.merged";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChangeLog() {
    }

    /** A payload from alternating keys and values; values may be null. */
    public static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            payload.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return payload;
    }

    public static void record(Connection c, Instant at, String kind, Map<String, ?> payload) throws SQLException {
        recordAll(c, at, kind, List.of(payload));
    }

    /** One row per payload, in order, sent as one batch. */
    public static void recordAll(Connection c, Instant at, String kind, List<? extends Map<String, ?>> payloads)
            throws SQLException {
        if (payloads.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO change_log (at, kind, payload_json) VALUES (?, ?, ?)")) {
            for (Map<String, ?> payload : payloads) {
                ps.setString(1, at.toString());
                ps.setString(2, kind);
                ps.setString(3, json(payload));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String json(Map<String, ?> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a change_log payload", e);
        }
    }
}
