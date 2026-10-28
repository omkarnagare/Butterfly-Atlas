package com.butterflyatlas.app.window;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Closing the last window quits the app (design §5, audit 2 and 3): one-second ticks, a fake clock. */
class QuitTimerTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong nanos = new AtomicLong(5 * SECOND);
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
    private final List<String> fallbacks = new ArrayList<>();
    private final List<String> quits = new ArrayList<>();

    private QuitTimer timer(int quitTicks, int startTicks, boolean expectWindow) {
        return new QuitTimer(quitTicks, startTicks, expectWindow, nanos::get, wall::get, fallbacks::add, quits::add);
    }

    private void ticks(QuitTimer timer, int n) {
        for (int i = 0; i < n; i++) {
            nanos.addAndGet(SECOND);
            wall.addAndGet(1000);
            timer.tick();
        }
    }

    @Test
    void quitsAtTheTenthSecondWithNoStream() {
        QuitTimer timer = timer(10, 60, true);
        timer.opened();
        timer.closed();

        ticks(timer, 9);
        assertThat(quits).isEmpty();
        ticks(timer, 1);

        assertThat(quits).singleElement().asString().contains("closed");
        ticks(timer, 5);
        assertThat(quits).hasSize(1); // once
    }

    @Test
    void aNewStreamCancelsTheCount() {
        QuitTimer timer = timer(10, 60, true);
        timer.opened();
        timer.closed();
        ticks(timer, 8);

        timer.opened(); // a reload
        ticks(timer, 30);
        timer.closed();
        ticks(timer, 9);

        assertThat(quits).isEmpty();
        ticks(timer, 1);
        assertThat(quits).hasSize(1);
    }

    @Test
    void severalWindowsKeepTheAppUntilTheLastOneCloses() {
        QuitTimer timer = timer(3, 60, true);
        timer.opened();
        timer.opened();
        timer.closed();

        ticks(timer, 10);
        assertThat(quits).isEmpty();
        assertThat(timer.streams()).isEqualTo(1);
        timer.closed();
        ticks(timer, 3);

        assertThat(quits).hasSize(1);
    }

    @Test
    void aLateTickBecauseTheComputerSleptRestartsTheCount() {
        QuitTimer timer = timer(10, 60, true);
        timer.opened();
        timer.closed();
        ticks(timer, 9);

        nanos.addAndGet(8 * 3600 * SECOND); // the lid was closed all night
        timer.tick();
        assertThat(quits).isEmpty();
        ticks(timer, 9);
        assertThat(quits).isEmpty(); // the window had time to reconnect; it did not need to
        ticks(timer, 1);

        assertThat(quits).hasSize(1);
    }

    @Test
    void aLateTickIsToldByTheWallClockTooBecauseTheMonotonicClockStandsStillWhileAMacOrLinuxSleeps() {
        QuitTimer timer = timer(10, 60, true);
        timer.opened();
        timer.closed();
        ticks(timer, 9);

        wall.addAndGet(8 * 3600 * 1000L); // the lid was closed all night: System.nanoTime did not move
        nanos.addAndGet(SECOND);
        timer.tick();
        assertThat(quits).isEmpty();
        ticks(timer, 9);
        assertThat(quits).isEmpty();
        ticks(timer, 1);

        assertThat(quits).hasSize(1);
    }

    @Test
    void aWallClockSetBackwardsIsNotALateTick() {
        QuitTimer timer = timer(10, 60, true);
        timer.opened();
        timer.closed();
        ticks(timer, 5);

        wall.addAndGet(-3600 * 1000L);
        ticks(timer, 5);

        assertThat(quits).hasSize(1);
    }

    @Test
    void neverQuitsBeforeAWindowHasConnectedWhenNoWindowIsExpected() {
        QuitTimer timer = timer(1, 5, false); // -Datlas.noBrowser=true, API tests

        ticks(timer, 1000);

        assertThat(quits).isEmpty();
        assertThat(fallbacks).isEmpty();
        timer.opened();
        timer.closed();
        ticks(timer, 1);
        assertThat(quits).hasSize(1); // once a window has connected, closing it quits
    }

    @Test
    void aWindowThatNeverConnectsTriesTheFallbackOnceThenQuits() {
        QuitTimer timer = timer(10, 60, true);

        ticks(timer, 59);
        assertThat(fallbacks).isEmpty();
        ticks(timer, 1);
        assertThat(fallbacks).singleElement().asString().contains("60 seconds");
        ticks(timer, 59);
        assertThat(quits).isEmpty();
        ticks(timer, 1);

        assertThat(fallbacks).hasSize(1);
        assertThat(quits).singleElement().asString().contains("no window").contains("120 seconds");
    }

    @Test
    void aWindowThatConnectsInTimeStopsTheStartCountdown() {
        QuitTimer timer = timer(10, 60, true);
        ticks(timer, 50);

        timer.opened();
        ticks(timer, 500);

        assertThat(fallbacks).isEmpty();
        assertThat(quits).isEmpty();
    }

    @Test
    void theRealTickerCallsTickEverySecondUntilClosed() throws Exception {
        List<String> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        QuitTimer timer = new QuitTimer(1, 60, true, System::nanoTime, got::add, got::add);
        timer.opened();
        timer.closed();
        timer.start();
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (got.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            timer.close();
        }

        assertThat(got).hasSize(1);
    }
}
