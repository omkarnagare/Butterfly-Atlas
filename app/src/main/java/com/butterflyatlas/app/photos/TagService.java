package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.OpenImports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tags on photos (design P6). Built-in tags can be relabelled but not deleted; the user's own tags
 * can be created, renamed and deleted (photos keep, the tag comes off them). A tag's key never
 * changes once made, so renaming is safe for everything that refers to it. Names are saved in their one form
 * ({@link TagNames}).
 */
public final class TagService {

    public static final String BUILTIN = "builtin";
    /** The tags' order, on {@code tag t}: built-in first by their order, then the user's by label. */
    public static final String ORDER = "t.source = 'user', t.sort_order, t.label COLLATE NOCASE";
    public static final String USER = "user";

    /** Keys of the built-in tags that mean "an adult" (life cycle, automatic cover). */
    public static final Set<String> ADULT_KEYS = PhotoRows.ADULT_KEYS;

    private static final int USER_SORT_ORDER = 1000;
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** {@code photoCount} counts live photos (not those in Recently deleted). */
    public record Tag(long id, String key, String label, String source, int photoCount) {
    }

    /** What V15 merged and numbered apart (tag-names design §4), the tags of more than 3 words now, and whether dismissed. */
    public record Note(List<Merged> merged, List<Numbered> numbered, List<String> longNames, boolean dismissed) {
    }

    /** A tag V15 merged into another; {@code photos} the live photos that had it. */
    public record Merged(String from, String into, int photos) {
    }

    /** A built-in V15 renamed apart from another built-in of the same name. */
    public record Numbered(String from, String to) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<Library> library;

    public TagService(Supplier<Library> library) {
        this.library = library;
    }

    /** Built-in tags in their order, then the user's by label. */
    public List<Tag> list() {
        return library.get().database().read(c -> tags(c, "", ps -> { }));
    }

    public Tag create(String rawLabel) {
        String label = TagNames.check(rawLabel);
        return library.get().database().write(c -> {
            requireNewLabel(c, label, null);
            String key = uniqueKey(c, keyFor(label));
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO tag (key, label, source, sort_order)"
                    + " VALUES (?, ?, 'user', ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, key);
                ps.setString(2, label);
                ps.setInt(3, USER_SORT_ORDER);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            return tag(c, id);
        });
    }

    public Tag rename(long id, String rawLabel) {
        String label = TagNames.check(rawLabel);
        return library.get().database().write(c -> {
            tag(c, id);
            requireNewLabel(c, label, id);
            try (PreparedStatement ps = c.prepareStatement("UPDATE tag SET label = ? WHERE id = ?")) {
                ps.setString(1, label);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return tag(c, id);
        });
    }

    /** The Home note of tag names. */
    public Note note() {
        return library.get().database().read(TagService::note);
    }

    /** Dismiss: the merged and numbered part is no longer shown (the tags over 3 words stay until renamed). */
    public Note dismissNote() {
        return library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE tag_name_note SET dismissed_at = ? WHERE id = 1")) {
                ps.setString(1, Instant.now().toString());
                ps.executeUpdate();
            }
            return note(c);
        });
    }

    private static Note note(Connection c) throws SQLException {
        List<Merged> merged = new ArrayList<>();
        List<Numbered> numbered = new ArrayList<>();
        boolean dismissed = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT payload_json, dismissed_at FROM tag_name_note WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                JsonNode payload;
                try {
                    payload = JSON.readTree(rs.getString(1));
                } catch (JsonProcessingException e) {
                    throw new SQLException("The tag names note is not readable: " + e.getOriginalMessage(), e);
                }
                for (JsonNode m : payload.path("merged")) {
                    merged.add(new Merged(m.path("from").asText(), m.path("into").asText(), m.path("photos").asInt()));
                }
                for (JsonNode n : payload.path("numbered")) {
                    numbered.add(new Numbered(n.path("from").asText(), n.path("to").asText()));
                }
                dismissed = rs.getString(2) != null;
            }
        }
        List<String> longNames = tags(c, "", ps -> { }).stream().map(Tag::label)
                .filter(label -> TagNames.words(label) > TagNames.MAX_WORDS).toList();
        return new Note(List.copyOf(merged), List.copyOf(numbered), longNames, dismissed);
    }

    /** Deletes a user tag and returns how many (live) photos it was taken off. */
    public int delete(long id) {
        Library lib = library.get();
        // the open imports whose items lose the tag: marked while the write runs, their revisions up once it committed
        try (OpenImports.Change change = lib.openImports().change()) {
            int count = lib.database().write(c -> {
                Tag tag = tag(c, id);
                if (BUILTIN.equals(tag.source())) {
                    throw AtlasException.invalid("Built-in tags can be renamed but not deleted.");
                }
                List<Long> runs = importsChoosing(c, id);
                lib.openImports().refuseWhileFiling(runs, OpenImports.TAG_FILING);
                change.add(runs);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM tag WHERE id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate(); // image_tag rows go with it (ON DELETE CASCADE)
                }
                return tag.photoCount();
            });
            change.committed();
            return count;
        }
    }

    /** The open imports with an item that chose the tag. */
    private static List<Long> importsChoosing(Connection c, long tagId) throws SQLException {
        List<Long> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT i.run_id FROM import_item_tag t JOIN import_item i"
                + " ON i.id = t.item_id JOIN import_run r ON r.id = i.run_id WHERE r.state = 'open' AND t.tag_id = ?"
                + " ORDER BY i.run_id")) {
            ps.setLong(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(rs.getLong(1));
                }
            }
        }
        return runs;
    }

    /** Replaces a live photo's tags. */
    public Photo setPhotoTags(long photoId, List<String> rawKeys) {
        List<String> keys = distinct(rawKeys);
        return library.get().database().write(c -> {
            PhotoRows.live(c, photoId);
            PhotoRows.requireTags(c, keys);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM image_tag WHERE image_id = ?")) {
                ps.setLong(1, photoId);
                ps.executeUpdate();
            }
            PhotoRows.addTags(c, photoId, keys);
            return PhotoRows.live(c, photoId);
        });
    }

    /** Adds and removes tags on several live photos at once; returns them in the order given. */
    public List<Photo> edit(List<Long> rawIds, List<String> rawAdd, List<String> rawRemove) {
        List<Long> ids = rawIds == null ? List.of() : rawIds.stream().filter(Objects::nonNull).distinct().toList();
        List<String> add = distinct(rawAdd);
        List<String> remove = distinct(rawRemove);
        if (ids.isEmpty()) {
            throw AtlasException.invalid(AtlasException.NO_PHOTOS_CHOSEN);
        }
        if (add.stream().anyMatch(remove::contains)) {
            throw AtlasException.invalid("A tag cannot be added and removed at the same time.");
        }
        return library.get().database().write(c -> {
            PhotoRows.live(c, ids); // every photo must be live before anything changes
            PhotoRows.requireTags(c, add);
            PhotoRows.requireTags(c, remove);
            // One batch of statements per kind, not a statement per photo and tag (1,000 photos: two batches).
            try (PreparedStatement adding = c.prepareStatement(
                    "INSERT OR IGNORE INTO image_tag (image_id, tag_id) SELECT ?, id FROM tag WHERE key = ?");
                 PreparedStatement removing = c.prepareStatement(
                    "DELETE FROM image_tag WHERE image_id = ? AND tag_id = (SELECT id FROM tag WHERE key = ?)")) {
                for (long id : ids) {
                    for (String key : add) {
                        adding.setLong(1, id);
                        adding.setString(2, key);
                        adding.addBatch();
                    }
                    for (String key : remove) {
                        removing.setLong(1, id);
                        removing.setString(2, key);
                        removing.addBatch();
                    }
                }
                if (!add.isEmpty()) {
                    adding.executeBatch();
                }
                if (!remove.isEmpty()) {
                    removing.executeBatch();
                }
            }
            return PhotoRows.live(c, ids);
        });
    }

    /** A label as a key: lower case, every run of other characters one {@code _} ("Mud-puddling" → "mud_puddling"). */
    static String keyFor(String label) {
        String key = NOT_ALPHANUMERIC.matcher(label.toLowerCase(Locale.ROOT)).replaceAll("_");
        key = key.replaceAll("^_+|_+$", "");
        return key.isEmpty() ? "tag" : key;
    }

    private static List<String> distinct(List<String> keys) {
        if (keys == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(keys.stream().filter(Objects::nonNull).map(String::strip)
                .filter(k -> !k.isEmpty()).toList()));
    }

    private static String uniqueKey(Connection c, String base) throws SQLException {
        String key = base;
        for (int n = 2; keyExists(c, key); n++) {
            key = base + "-" + n;
        }
        return key;
    }

    private static boolean keyExists(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tag WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void requireNewLabel(Connection c, String label, Long except) throws SQLException {
        String wanted = label.toLowerCase(Locale.ROOT);
        try (PreparedStatement ps = c.prepareStatement("SELECT id, label FROM tag");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                boolean same = except != null && rs.getLong(1) == except;
                if (!same && rs.getString(2).toLowerCase(Locale.ROOT).equals(wanted)) {
                    throw AtlasException.invalid("There is already a tag called \"" + rs.getString(2) + "\".");
                }
            }
        }
    }

    private static Tag tag(Connection c, long id) throws SQLException {
        List<Tag> found = tags(c, " WHERE t.id = ?", ps -> ps.setLong(1, id));
        if (found.isEmpty()) {
            throw AtlasException.notFound("There is no such tag.");
        }
        return found.get(0);
    }

    private static List<Tag> tags(Connection c, String where, PhotoRows.Binder binder) throws SQLException {
        List<Tag> tags = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.id, t.key, t.label, t.source,"
                + " (SELECT COUNT(*) FROM image_tag it JOIN image i ON i.id = it.image_id"
                + " WHERE it.tag_id = t.id AND i.deleted_at IS NULL)"
                + " FROM tag t" + where + " ORDER BY " + ORDER)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tags.add(new Tag(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
                }
            }
        }
        return tags;
    }
}
