package com.butterflyatlas.app.taxa;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * Words the user adds so a taxon turns up in search (design D3). Stored as {@code source = 'user'}
 * synonyms, so bundle updates never touch them; never used to suggest merges.
 */
public final class SearchWordService {

    public static final int MAX_LENGTH = 80;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final Supplier<Database> database;
    private final Clock clock;

    public SearchWordService(Supplier<Database> database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public List<String> list(String taxonId) {
        return database.get().read(c -> {
            requireTaxon(c, taxonId);
            return TaxonService.names(c, taxonId, "user");
        });
    }

    public List<String> add(String taxonId, String rawWord) {
        String word = clean(rawWord);
        return database.get().write(c -> {
            requireTaxon(c, taxonId);
            if (!alreadyNamed(c, taxonId, word)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES (?, ?, 'user', ?)")) {
                    ps.setString(1, taxonId);
                    ps.setString(2, word);
                    ps.setString(3, clock.instant().toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO taxon_fts (taxon_id, kind, text) VALUES (?, 'word', ?)")) {
                    ps.setString(1, taxonId);
                    ps.setString(2, word);
                    ps.executeUpdate();
                }
            }
            return TaxonService.names(c, taxonId, "user");
        });
    }

    public List<String> remove(String taxonId, String rawWord) {
        String word = clean(rawWord);
        return database.get().write(c -> {
            requireTaxon(c, taxonId);
            Optional<String> stored = storedUserWord(c, taxonId, word);
            if (stored.isPresent()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM taxon_synonym WHERE taxon_id = ? AND source = 'user' AND name = ?")) {
                    ps.setString(1, taxonId);
                    ps.setString(2, stored.get());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM taxon_fts WHERE taxon_id = ? AND kind = 'word' AND text = ?")) {
                    ps.setString(1, taxonId);
                    ps.setString(2, stored.get());
                    ps.executeUpdate();
                }
            }
            return TaxonService.names(c, taxonId, "user");
        });
    }

    static String clean(String raw) {
        String word = raw == null ? "" : WHITESPACE.matcher(raw.strip()).replaceAll(" ");
        if (word.isEmpty()) {
            throw AtlasException.invalid("Type a search word.");
        }
        if (word.length() > MAX_LENGTH) {
            throw AtlasException.invalid("Keep search words to " + MAX_LENGTH + " characters or fewer.");
        }
        return word;
    }

    private static void requireTaxon(Connection c, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM taxon WHERE id = ?")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AtlasException.noTaxon(taxonId);
                }
            }
        }
    }

    private static boolean alreadyNamed(Connection c, String taxonId, String word) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM taxon WHERE id = ? AND (lower(name) = lower(?) OR lower(common_name) = lower(?))"
                        + " UNION SELECT 1 FROM taxon_synonym WHERE taxon_id = ? AND name = ?")) {
            ps.setString(1, taxonId);
            ps.setString(2, word);
            ps.setString(3, word);
            ps.setString(4, taxonId);
            ps.setString(5, word);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Optional<String> storedUserWord(Connection c, String taxonId, String word) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM taxon_synonym WHERE taxon_id = ? AND source = 'user' AND name = ?")) {
            ps.setString(1, taxonId);
            ps.setString(2, word);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }
}
