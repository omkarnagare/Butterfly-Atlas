package com.butterflyatlas.app.db;

import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A database that stayed busy (multi-window contract §6, audit A16): found down the cause chain, tried again by jobs. */
class DatabaseBusyTest {

    private static RuntimeException busy() {
        return new DatabaseException("write failed", new SQLiteException("[SQLITE_BUSY]", SQLiteErrorCode.SQLITE_BUSY));
    }

    @Test
    void busyIsFoundAnywhereDownTheCauseChainAndOnlyBusy() {
        assertThat(DatabaseBusy.is(busy())).isTrue();
        assertThat(DatabaseBusy.is(new IllegalStateException("x", new RuntimeException("y",
                new SQLiteException("[SQLITE_BUSY_SNAPSHOT]", SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT))))).isTrue();
        assertThat(DatabaseBusy.is(new SQLException("database is locked", null, 5))).isTrue();
        assertThat(DatabaseBusy.is(new DatabaseException("x", new SQLiteException("[SQLITE_IOERR]",
                SQLiteErrorCode.SQLITE_IOERR)))).isFalse();
        assertThat(DatabaseBusy.is(new IllegalStateException("no cause"))).isFalse();
        RuntimeException loop = new RuntimeException("a");
        RuntimeException other = new RuntimeException("b", loop);
        loop.initCause(other);
        assertThat(DatabaseBusy.is(loop)).as("a looping chain ends").isFalse();
    }

    @Test
    void aJobTriesABusyItemThreeMoreTimesThenGivesUp() {
        AtomicInteger tries = new AtomicInteger();

        assertThat(DatabaseBusy.retry("testing", () -> {
            if (tries.incrementAndGet() <= 3) {
                throw busy();
            }
            return "done";
        })).isEqualTo("done");
        assertThat(tries.get()).isEqualTo(4);

        tries.set(0);
        assertThatThrownBy(() -> DatabaseBusy.retry("testing", () -> {
            tries.incrementAndGet();
            throw busy();
        })).satisfies(e -> assertThat(DatabaseBusy.is(e)).isTrue());
        assertThat(tries.get()).isEqualTo(4);

        tries.set(0);
        assertThatThrownBy(() -> DatabaseBusy.retry("testing", () -> {
            tries.incrementAndGet();
            throw new IllegalStateException("not busy");
        })).hasMessage("not busy");
        assertThat(tries.get()).as("anything else is not tried again").isEqualTo(1);
    }
}
