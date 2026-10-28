package com.butterflyatlas.importer.inat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Polite HTTP access to iNaturalist: one request at a time, ≥1.1 s apart, with retries. */
public final class HttpTransport implements Transport {

    static final Duration MIN_INTERVAL = Duration.ofMillis(1100);
    static final int MAX_ATTEMPTS = 5;

    /** Sends one GET request. */
    @FunctionalInterface
    interface Sender {
        Response send(URI uri) throws IOException, InterruptedException;
    }

    /** Waits for the given duration. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    record Response(int status, String body) {
    }

    private final Sender sender;
    private final Sleeper sleeper;
    private final Clock clock;
    private Instant lastRequest = Instant.EPOCH;

    HttpTransport(Sender sender, Sleeper sleeper, Clock clock) {
        this.sender = sender;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public static HttpTransport create(String userAgent) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Sender sender = uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
        return new HttpTransport(sender, duration -> Thread.sleep(duration.toMillis()), Clock.systemUTC());
    }

    @Override
    public String get(URI uri) throws IOException {
        for (int attempt = 1; ; attempt++) {
            waitForTurn();
            Response response;
            try {
                response = sender.send(uri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling " + uri, e);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    String reason = String.valueOf(e.getMessage() == null ? e : e.getMessage());
                    throw new IOException(reason + " calling " + uri
                            + " (gave up after " + MAX_ATTEMPTS + " attempts)", e);
                }
                pause(backoff(attempt));
                continue;
            }
            if (response.status() == 200) {
                return response.body();
            }
            String failure = "iNaturalist returned HTTP " + response.status() + " for " + uri;
            boolean retryable = response.status() == 429 || response.status() >= 500;
            if (!retryable) {
                throw new IOException(failure);
            }
            if (attempt == MAX_ATTEMPTS) {
                throw new IOException(failure + " (gave up after " + MAX_ATTEMPTS + " attempts)");
            }
            pause(backoff(attempt));
        }
    }

    /** 2 s, 4 s, 8 s, 16 s. */
    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(1L << attempt);
    }

    private void waitForTurn() throws IOException {
        Duration sinceLast = Duration.between(lastRequest, clock.instant());
        if (sinceLast.isNegative()) {
            sinceLast = Duration.ZERO;
        }
        if (sinceLast.compareTo(MIN_INTERVAL) < 0) {
            pause(MIN_INTERVAL.minus(sinceLast));
        }
        lastRequest = clock.instant();
    }

    private void pause(Duration duration) throws IOException {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to call iNaturalist", e);
        }
    }
}
