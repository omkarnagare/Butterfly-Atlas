package com.butterflyatlas.app.custom;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.photos.TaxonRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The name rules of a custom species (5a contract §2; design D6): a strict binomial, an optional common name, and the
 * names it may not take (an active official species' name or bundled synonym, another custom species' name).
 */
final class CustomNames {

    static final int MAX_LENGTH = 100;
    static final String BAD_NAME = "Enter the scientific name as genus and species, e.g. Neptis sankara.";
    static final String BAD_COMMON = "The common name can be at most " + MAX_LENGTH + " characters.";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BINOMIAL = Pattern.compile("^[A-Z][a-z]+ [a-z]+(-[a-z]+)*$");

    private CustomNames() {
    }

    /** 400 {@code invalid}. */
    static AtlasException invalid(String message) {
        return new AtlasException(AtlasException.Kind.INVALID, ErrorCodes.INVALID, message);
    }

    /** NFC, trimmed, single spaces. */
    private static String tidy(String raw) {
        return raw == null ? "" : WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()).replaceAll(" ");
    }

    /**
     * The scientific name as stored: tidied, the genus's first letter upper case and everything else lower case
     * ({@code "neptis  Sankara"} → {@code "Neptis sankara"}); 400 {@code invalid} unless it is then a binomial of at most
     * {@value #MAX_LENGTH} characters.
     */
    static String name(String raw) {
        String tidy = tidy(raw);
        String name = tidy.isEmpty() ? tidy : tidy.substring(0, 1).toUpperCase(Locale.ROOT) + tidy.substring(1).toLowerCase(Locale.ROOT);
        if (name.length() > MAX_LENGTH || !BINOMIAL.matcher(name).matches()) {
            throw invalid(BAD_NAME);
        }
        return name;
    }

    /** The common name as stored: tidied, null when empty; 400 {@code invalid} over {@value #MAX_LENGTH} characters. */
    static String common(String raw) {
        String common = tidy(raw);
        if (common.length() > MAX_LENGTH) {
            throw invalid(BAD_COMMON);
        }
        return common.isEmpty() ? null : common;
    }

    /**
     * Fails with {@link NameBlocked} when {@code name} (already normalised) is an active official species' name or one
     * of its bundled synonyms ({@code in_list}), or another custom species' name ({@code name_exists}; {@code except},
     * the species being renamed, aside). Compared without case; the user's search words never block.
     */
    static void requireFree(Connection c, String name, String except) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name, common_name FROM taxon WHERE rank = 'species'"
                + " AND source = 'official' AND active = 1 AND lower(name) = lower(?) ORDER BY id LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String common = rs.getString(3);
                    throw new NameBlocked(NameBlocked.IN_LIST, rs.getString(2) + " is already in the list"
                            + (common == null ? "." : " as " + common + "."), new TaxonRef(rs.getString(1), rs.getString(2), common));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT t.id, t.name, t.common_name FROM taxon_synonym s"
                + " JOIN taxon t ON t.id = s.taxon_id WHERE s.source = 'bundle' AND s.name = ? AND t.rank = 'species'"
                + " AND t.source = 'official' AND t.active = 1 ORDER BY t.id LIMIT 1")) {
            ps.setString(1, name); // taxon_synonym.name is COLLATE NOCASE
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String common = rs.getString(3);
                    throw new NameBlocked(NameBlocked.IN_LIST, name + " is already in the list as " + rs.getString(2)
                            + (common == null ? "." : " (" + common + ")."), new TaxonRef(rs.getString(1), rs.getString(2), common));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name, common_name FROM taxon WHERE source = 'manual'"
                + " AND lower(name) = lower(?) AND id IS NOT ? ORDER BY id LIMIT 1")) {
            ps.setString(1, name);
            ps.setString(2, except);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new NameBlocked(NameBlocked.NAME_EXISTS, "You already added " + rs.getString(2) + ".",
                            new TaxonRef(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
    }
}
