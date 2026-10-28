package com.butterflyatlas.app.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlListsTest {

    @Test
    void chunksHoldAtMostFiveHundredValuesInOrder() {
        List<Long> ids = LongStream.rangeClosed(1, 1001).boxed().toList();

        List<List<Long>> chunks = SqlLists.chunks(ids);

        assertThat(chunks).extracting(List::size).containsExactly(500, 500, 1);
        assertThat(chunks.get(0).get(0)).isEqualTo(1L);
        assertThat(chunks.get(1).get(0)).isEqualTo(501L);
        assertThat(chunks.get(2)).containsExactly(1001L);
        assertThat(SqlLists.chunks(List.of())).isEmpty();
        assertThat(SqlLists.chunks(List.of("a", "b"))).containsExactly(List.of("a", "b"));
    }

    @Test
    void marksAreOneQuestionMarkPerValue() {
        assertThat(SqlLists.marks(1)).isEqualTo("?");
        assertThat(SqlLists.marks(3)).isEqualTo("?, ?, ?");
    }

    @Test
    void bindSetsLongsAndStringsFromAPositionAndAnswersTheNext() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             PreparedStatement ps = c.prepareStatement("SELECT ?, typeof(?), ?, typeof(?), ?")) {
            ps.setString(1, "first");

            int next = SqlLists.bind(ps, 2, List.of(7L, 8L));
            next = SqlLists.bind(ps, next, List.of("P4", "x"));

            assertThat(next).isEqualTo(6);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("first");
                assertThat(rs.getString(2)).isEqualTo("integer");
                assertThat(rs.getLong(3)).isEqualTo(8L);
                assertThat(rs.getString(4)).isEqualTo("text");
                assertThat(rs.getString(5)).isEqualTo("x");
            }
        }
    }

    @Test
    void bindRefusesOtherTypes() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             PreparedStatement ps = c.prepareStatement("SELECT ?")) {
            assertThatThrownBy(() -> SqlLists.bind(ps, 1, List.of(1.5))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
