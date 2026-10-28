package com.butterflyatlas.importer.inat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTransportTest {

    private static final URI URL = URI.create("https://api.inaturalist.org/v2/taxa/1");

    private final MutableClock clock = new MutableClock();
    private final List<Duration> sleeps = new ArrayList<>();
    private int attempts;

    private HttpTransport transportReplying(HttpTransport.Response... responses) {
        Deque<HttpTransport.Response> queue = new ArrayDeque<>(List.of(responses));
        return new HttpTransport(uri -> {
            attempts++;
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        }, clock);
    }

    @Test
    void returnsTheBodyOfASuccessfulResponse() throws Exception {
        HttpTransport transport = transportReplying(new HttpTransport.Response(200, "{\"ok\":true}"));

        assertThat(transport.get(URL)).isEqualTo("{\"ok\":true}");
        assertThat(attempts).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesRateLimitsAndServerErrorsWithBackoff() throws Exception {
        HttpTransport transport = transportReplying(
                new HttpTransport.Response(429, ""),
                new HttpTransport.Response(503, ""),
                new HttpTransport.Response(200, "done"));

        assertThat(transport.get(URL)).isEqualTo("done");
        assertThat(attempts).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void givesUpAfterFiveAttempts() {
        HttpTransport transport = transportReplying(new HttpTransport.Response(503, ""));

        assertThatThrownBy(() -> transport.get(URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 503")
                .hasMessageContaining("gave up after 5 attempts");
        assertThat(attempts).isEqualTo(5);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(8), Duration.ofSeconds(16));
    }

    @Test
    void givesUpOnPersistentNetworkFailures() {
        HttpTransport transport = new HttpTransport(uri -> {
            attempts++;
            throw new IOException("connection reset");
        }, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        }, clock);

        assertThatThrownBy(() -> transport.get(URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("connection reset")
                .hasMessageContaining("gave up after 5 attempts");
        assertThat(attempts).isEqualTo(5);
    }

    @Test
    void doesNotRetryClientErrors() {
        HttpTransport transport = transportReplying(new HttpTransport.Response(404, ""));

        assertThatThrownBy(() -> transport.get(URL)).hasMessageContaining("HTTP 404");
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void retriesNetworkFailures() throws Exception {
        Deque<Object> outcomes = new ArrayDeque<>(List.of(new IOException("connection reset"),
                new HttpTransport.Response(200, "ok")));
        HttpTransport transport = new HttpTransport(uri -> {
            attempts++;
            Object next = outcomes.poll();
            if (next instanceof IOException e) {
                throw e;
            }
            return (HttpTransport.Response) next;
        }, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        }, clock);

        assertThat(transport.get(URL)).isEqualTo("ok");
        assertThat(attempts).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void spacesRequestsAtLeastOnePointOneSecondsApart() throws Exception {
        HttpTransport transport = transportReplying(new HttpTransport.Response(200, "a"));

        transport.get(URL);
        clock.advance(Duration.ofMillis(300));
        transport.get(URL);

        assertThat(sleeps).containsExactly(Duration.ofMillis(800));
    }

    /** A clock the test moves forward by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
