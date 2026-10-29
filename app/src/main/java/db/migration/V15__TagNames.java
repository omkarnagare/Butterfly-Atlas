package db.migration;

import com.butterflyatlas.app.photos.TagNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every tag's name in its one form (tag-names design §3). Tags that become the same name (without case) are merged
 * into the built-in one, or else the oldest: its photos and import items get the tag kept. Two built-ins are never
 * merged (neither can be deleted); the newer gets {@code _2} (or the next number free). What was merged or numbered is
 * kept in {@code tag_name_note} for the Home note; plain renames are not. Java, not SQL: SQLite has no regular expressions.
 */
public class V15__TagNames extends BaseJavaMigration {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Tag(long id, String label, boolean builtin) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE tag_name_note ("
                    + " id INTEGER PRIMARY KEY CHECK (id = 1),"
                    + " payload_json TEXT NOT NULL,"
                    + " dismissed_at TEXT)");
        }
        // the tags by their formed name without case, each list built-ins first, then oldest first
        Map<String, List<Tag>> byName = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, label, source = 'builtin' FROM tag ORDER BY source <> 'builtin', id")) {
            while (rs.next()) {
                Tag tag = new Tag(rs.getLong(1), rs.getString(2), rs.getBoolean(3));
                byName.computeIfAbsent(TagNames.form(tag.label()).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(tag);
            }
        }
        Set<String> taken = new HashSet<>(byName.keySet());
        Map<Long, Map<String, Object>> merged = new TreeMap<>(); // by the merged tag's id: the order they were made
        Map<Long, Map<String, Object>> numbered = new TreeMap<>();
        for (List<Tag> same : byName.values()) {
            Tag kept = same.get(0);
            String name = TagNames.form(kept.label());
            relabel(c, kept.id(), name);
            for (Tag other : same.subList(1, same.size())) {
                if (other.builtin()) {
                    String apart = apart(name, taken);
                    relabel(c, other.id(), apart);
                    numbered.put(other.id(), entry("from", other.label(), "to", apart));
                } else {
                    merged.put(other.id(), entry("from", other.label(), "into", name, "photos", merge(c, other.id(), kept.id())));
                }
            }
        }
        if (!merged.isEmpty() || !numbered.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO tag_name_note (id, payload_json) VALUES (1, ?)")) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("merged", merged.values());
                payload.put("numbered", numbered.values());
                ps.setString(1, JSON.writeValueAsString(payload));
                ps.executeUpdate();
            }
        }
    }

    /** A note entry, its fields in the order given. */
    private static Map<String, Object> entry(Object... keysAndValues) {
        Map<String, Object> entry = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entry.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return entry;
    }

    /** {@code name_2}, or the first number after it not taken (without case); taken from then on. */
    private static String apart(String name, Set<String> taken) {
        for (int n = 2; ; n++) {
            String candidate = name + "_" + n;
            if (taken.add(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    private static void relabel(Connection c, long id, String label) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE tag SET label = ? WHERE id = ?")) {
            ps.setString(1, label);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Moves a tag's photos and import items to the tag kept and deletes it; returns how many live photos had it. */
    private static int merge(Connection c, long from, long into) throws SQLException {
        int photos;
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM image_tag it JOIN image i ON i.id = it.image_id"
                + " WHERE it.tag_id = ? AND i.deleted_at IS NULL")) {
            ps.setLong(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                photos = rs.getInt(1);
            }
        }
        for (String sql : List.of(
                "INSERT OR IGNORE INTO image_tag (image_id, tag_id) SELECT image_id, ? FROM image_tag WHERE tag_id = ?",
                "INSERT OR IGNORE INTO import_item_tag (item_id, tag_id) SELECT item_id, ? FROM import_item_tag WHERE tag_id = ?")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, into);
                ps.setLong(2, from);
                ps.executeUpdate();
            }
        }
        for (String sql : List.of("DELETE FROM image_tag WHERE tag_id = ?", "DELETE FROM import_item_tag WHERE tag_id = ?",
                "DELETE FROM tag WHERE id = ?")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, from);
                ps.executeUpdate();
            }
        }
        return photos;
    }
}
