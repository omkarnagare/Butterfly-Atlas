package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.updates.UpdateViews.Match;
import com.butterflyatlas.app.updates.UpdateViews.MatchCustom;
import com.butterflyatlas.app.updates.UpdateViews.MatchOfficial;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom species that now match an official species (5b contract §5, design D15): after each completed bundle update, a
 * custom species whose name equals (any case) an active official species' name or one of its bundled synonyms (never the
 * user's search words) is offered for merging, one {@code custom_match} row per pair at the new version unless one is
 * open already. A declined match is offered again after the next update if it still matches.
 */
final class CustomMatches {

    static final String OPEN = "open";
    static final String MERGED = "merged";
    static final String DECLINED = "declined";

    private CustomMatches() {
    }

    /** The matches of the library as it is now, offered at {@code version}; returns how many were offered. */
    static int offer(Connection c, String version, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO custom_match (custom_id, official_id,"
                + " offered_version, state) SELECT DISTINCT u.id, o.id, ?, 'open' FROM taxon u JOIN taxon o"
                + " ON o.rank = 'species' AND o.source = 'official' AND o.active = 1"
                + " AND (lower(o.name) = lower(u.name) OR EXISTS (SELECT 1 FROM taxon_synonym s WHERE s.taxon_id = o.id"
                + " AND s.source = 'bundle' AND s.name = u.name))"
                + " WHERE u.source = 'manual' AND u.rank = 'species' AND NOT EXISTS (SELECT 1 FROM custom_match m"
                + " WHERE m.custom_id = u.id AND m.official_id = o.id AND m.state = 'open')")) {
            ps.setString(1, version);
            return ps.executeUpdate();
        }
    }

    /** The open matches whose custom and official species are both still there (the official one active), by name. */
    static List<Match> open(Connection c) throws SQLException {
        TaxonSnapshot snapshot = TaxonSnapshot.load(c);
        List<Match> matches = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT m.custom_id, m.official_id, m.offered_version FROM custom_match m"
                + " JOIN taxon u ON u.id = m.custom_id AND u.source = 'manual' JOIN taxon o ON o.id = m.official_id"
                + " AND o.active = 1 WHERE m.state = 'open' ORDER BY lower(u.name), m.custom_id, m.official_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TaxonSnapshot.Row custom = snapshot.find(rs.getString(1)).orElseThrow();
                TaxonSnapshot.Row official = snapshot.find(rs.getString(2)).orElseThrow();
                matches.add(new Match(new MatchCustom(custom.id(), custom.name(), custom.common(), custom.photos()),
                        new MatchOfficial(official.id(), official.name(), official.common(), snapshot.family(official)),
                        rs.getString(3)));
            }
        }
        return matches;
    }

    /** How many matches {@link #open} lists, in one count. */
    static int count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM custom_match m JOIN taxon u ON u.id = m.custom_id"
                + " AND u.source = 'manual' JOIN taxon o ON o.id = m.official_id AND o.active = 1 WHERE m.state = 'open'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Whether the match of these two is open. */
    static boolean isOpen(Connection c, String customId, String officialId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM custom_match WHERE custom_id = ? AND official_id = ?"
                + " AND state = 'open'")) {
            ps.setString(1, customId);
            ps.setString(2, officialId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The open match of these two decided ({@code merged} or {@code declined}); false when there was none open. */
    static boolean decide(Connection c, String customId, String officialId, String state, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE custom_match SET state = ?, decided_at = ? WHERE custom_id = ?"
                + " AND official_id = ? AND state = 'open'")) {
            ps.setString(1, state);
            ps.setString(2, now.toString());
            ps.setString(3, customId);
            ps.setString(4, officialId);
            return ps.executeUpdate() > 0;
        }
    }
}
