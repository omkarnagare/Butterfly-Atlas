package com.butterflyatlas.app.window;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What a launch does when another copy may be running (design §4, audit 4): reads the pointer file; a stale one (its
 * process gone) is removed and the start goes on; a live one is asked ({@code POST /launch/new}) for a one-time key and the
 * window opens on it (multi-window design §3: one more window, no notice). A copy that is closing, still starting or stuck is asked again
 * for up to {@link #WAIT}; the pointer of a closing copy is gone at once, so the start then goes on (the library's lock is
 * waited for by the caller). No answer at all is {@link Outcome#NOT_ANSWERING}.
 */
public final class SecondLaunch {

    public enum Outcome {
        /** Nothing is running: this launch starts the app. */
        NOT_RUNNING,
        /** A window was opened on the running copy: this launch ends. */
        OPENED,
        /** A copy is running (its pointer, its process) but does not answer: this launch ends with the message page. */
        NOT_ANSWERING
    }

    /** The running copy's answer to {@code POST /launch/new}. */
    public record Reply(int status, String body) {
    }

    /** Asks the copy on {@code port} for a launch key, sending {@code key} as {@code X-Atlas-Key}. */
    @FunctionalInterface
    public interface Ask {
        Reply post(int port, String key) throws IOException;
    }

    /** How long a copy that does not answer is tried for. */
    public static final Duration WAIT = Duration.ofSeconds(30);
    private static final long RETRY_MILLIS = 250;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InstancePointer pointer;
    private final Ask ask;
    private final LongSupplier nanos;
    private final LongConsumer sleep;

    /** With the pointer, how to ask, a clock and a sleep (milliseconds); tests give fakes. */
    public SecondLaunch(InstancePointer pointer, Ask ask, LongSupplier nanos, LongConsumer sleep) {
        this.pointer = pointer;
        this.ask = ask;
        this.nanos = nanos;
        this.sleep = sleep;
    }

    /** This system: the JDK's HTTP client, the real clock and sleep. */
    public static SecondLaunch system(InstancePointer pointer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new SecondLaunch(pointer, (port, key) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + SessionKeys.NEW_LAUNCH_PATH))
                    .timeout(Duration.ofSeconds(3)).header(SessionKeys.KEY_HEADER, key)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                return new Reply(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }, System::nanoTime, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Runs the check. When a copy answers, {@code open} is given the address of the window to open: its first use is the
     * key just given, every later one (the fallback of the window launcher) asks for a fresh key.
     */
    public Outcome run(Consumer<Supplier<URI>> open) {
        return run(open, false);
    }

    /**
     * As {@link #run(Consumer)}; {@code starting}: another copy holds the config folder's instance lock, so it is running
     * or still opening its library. With no pointer yet (or a stale one from a crash) the pointer is read again until it
     * appears, for up to {@link #WAIT}, and this launch is never {@link Outcome#NOT_RUNNING}.
     */
    public Outcome run(Consumer<Supplier<URI>> open, boolean starting) {
        long deadline = nanos.getAsLong() + WAIT.toNanos();
        while (true) {
            Optional<InstancePointer.Info> read = pointer.read();
            if (read.isEmpty() && !starting) {
                return Outcome.NOT_RUNNING;
            }
            if (read.isPresent() && !read.get().running()) {
                pointer.removeIfOurs(read.get().key()); // left behind by a crash: replaced by the copy that starts now
                if (!starting) {
                    return Outcome.NOT_RUNNING;
                }
                read = Optional.empty();
            }
            Optional<InstancePointer.Info> info = read;
            if (info.isEmpty()) {
                if (nanos.getAsLong() >= deadline) {
                    return Outcome.NOT_ANSWERING;
                }
                sleep.accept(RETRY_MILLIS);
                continue;
            }
            Optional<String> key = key(info.get());
            if (key.isPresent()) {
                String[] first = {key.get()};
                open.accept(() -> address(info.get().port(), first[0] != null ? first[0] : key(info.get()).orElse(null),
                        first));
                return Outcome.OPENED;
            }
            if (nanos.getAsLong() >= deadline) {
                return Outcome.NOT_ANSWERING;
            }
            sleep.accept(RETRY_MILLIS);
        }
    }

    /** The address to open: the key in hand, once, else a new one asked for; without any, the plain address. */
    private static URI address(int port, String key, String[] first) {
        first[0] = null;
        String base = "http://127.0.0.1:" + port + "/";
        return key == null ? URI.create(base) : URI.create(base + SessionKeys.LAUNCH_QUERY + URLEncoder.encode(key, StandardCharsets.UTF_8));
    }

    private Optional<String> key(InstancePointer.Info info) {
        try {
            Reply reply = ask.post(info.port(), info.key());
            if (reply.status() != 200) {
                return Optional.empty(); // closing (503), a wrong key (401): the pointer is read again
            }
            JsonNode launch = JSON.readTree(reply.body()).get("launch");
            return launch == null || launch.asText().isBlank() ? Optional.empty() : Optional.of(launch.asText());
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // not listening yet, or stuck
        }
    }
}
