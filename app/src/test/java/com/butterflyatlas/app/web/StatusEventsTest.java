package com.butterflyatlas.app.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Windows kept in step (multi-window design §6, contract §2, audit A11): a status event when the status changed. */
class StatusEventsTest {

    private final AtomicReference<String> status = new AtomicReference<>("{\"a\":1}");
    private final AtomicInteger reads = new AtomicInteger();
    private final StatusEvents events = new StatusEvents(() -> {
        reads.incrementAndGet();
        return status.get();
    });

    private static final class Window implements StatusEvents.Stream {
        int told;

        @Override
        public void status() {
            told++;
        }
    }

    @Test
    void everyStreamIsToldOnceWhenItOpensEvenAfterAReconnect() {
        Window window = new Window();

        events.opened(window);
        assertThat(window.told).isEqualTo(1);
        events.closed(window);
        events.opened(window);

        assertThat(window.told).isEqualTo(2);
    }

    @Test
    void aStreamIsToldWhenTheStatusDiffersFromWhatItWasLastToldAndOnlyThen() {
        Window window = new Window();
        events.opened(window);

        events.check();
        assertThat(window.told).as("nothing changed").isEqualTo(1);
        status.set("{\"a\":2}");
        events.check();
        events.check();

        assertThat(window.told).isEqualTo(2);
    }

    @Test
    void eachStreamIsComparedWithWhatItWasLastTold() {
        Window first = new Window();
        events.opened(first);
        status.set("{\"a\":2}");
        Window second = new Window();
        events.opened(second); // told of {"a":2} as it opened

        events.check();

        assertThat(first.told).isEqualTo(2);
        assertThat(second.told).isEqualTo(1);
    }

    @Test
    void aStreamThatThrowsIsDroppedAndTheOthersAreStillTold() {
        Window first = new Window();
        AtomicInteger thrown = new AtomicInteger();
        StatusEvents.Stream broken = () -> {
            if (thrown.incrementAndGet() > 1) { // told at its open, then its write fails
                throw new IllegalStateException("the window is gone");
            }
        };
        Window last = new Window();
        events.opened(first);
        events.opened(broken);
        events.opened(last);
        status.set("{\"a\":2}");

        events.check();

        assertThat(first.told).isEqualTo(2);
        assertThat(last.told).isEqualTo(2);
        assertThat(events.open()).as("the broken stream is dropped").isEqualTo(2);
        status.set("{\"a\":3}");
        events.check();
        assertThat(thrown.get()).as("never told again").isEqualTo(2);
        assertThat(first.told).isEqualTo(3);
    }

    @Test
    void withNoStreamOpenTheStatusIsNotEvenRead() {
        Window window = new Window();
        events.opened(window);
        events.closed(window);
        reads.set(0);

        events.check();

        assertThat(reads.get()).isZero();
        assertThat(events.open()).isZero();
    }

    @Test
    void aStatusThatCannotBeReadSendsNothingAndTheNextCheckGoesOn() {
        StatusEvents failing = new StatusEvents(() -> {
            throw new IllegalStateException("not now");
        });
        Window other = new Window();
        failing.opened(other);
        failing.check();

        assertThat(other.told).as("told once at the open, the failed reads send nothing more").isEqualTo(1);
    }
}
