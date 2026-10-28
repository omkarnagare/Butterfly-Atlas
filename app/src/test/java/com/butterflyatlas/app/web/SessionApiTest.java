package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.AtlasVersion;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.window.QuitTimer;
import com.butterflyatlas.app.window.SessionKeys;
import com.butterflyatlas.app.window.WindowLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-launch secret over HTTP (contract §2): the launch exchange, the guard, the session's facts, the stream. */
class SessionApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final TestClock clock = new TestClock();
    private final SessionKeys keys = new SessionKeys(clock);
    private final AtomicReference<WindowLauncher.Mode> mode = new AtomicReference<>(WindowLauncher.Mode.WINDOW);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final List<String> quits = new ArrayList<>();
    private final AtomicBoolean windowOpens = new AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicInteger windowsAsked = new java.util.concurrent.atomic.AtomicInteger();
    private QuitTimer quit;
    private AtlasServices services;
    private AtlasServer server;

    @BeforeEach
    void start() {
        quit = new QuitTimer(1, 60, true, System::nanoTime, why -> { }, quits::add);
        services = TestServices.unconfigured(home);
        server = AtlasServer.start(0, services, new Session(keys, quit, mode::get, closing::get, home.resolve("config"),
                home.resolve("config").resolve("logs"), Duration.ofMillis(100), () -> {
                    windowsAsked.incrementAndGet();
                    return windowOpens.get();
                }));
    }

    @AfterEach
    void stop() {
        server.close();
        services.exports().close();
        services.thumbnails().close();
    }

    private RawHttp.Response get(String path, Map<String, String> headers) throws Exception {
        return RawHttp.send(server.port(), "GET", path, headers, null);
    }

    private Map<String, String> cookie() {
        return Map.of("Cookie", SessionKeys.cookieName(server.port()) + "=" + keys.secret());
    }

    @Test
    void withoutTheCookieTheApiAnswers401AndAPersonOpeningTheAddressSeesWhatToDo() throws Exception {
        RawHttp.Response api = get("/api/health", Map.of());
        assertThat(api.status()).isEqualTo(401);
        JsonNode error = JSON.readTree(api.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("no_session");
        assertThat(error.get("message").asText()).isEqualTo("To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.");

        RawHttp.Response page = get("/", Map.of());
        assertThat(page.status()).isEqualTo(401);
        assertThat(page.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(page.headers().get("content-type")).startsWith("text/html");
        assertThat(page.body()).contains("To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.").doesNotContain("reload from the address");

        assertThat(get("/index.html", Map.of()).status()).isEqualTo(401); // the UI's files too
        assertThat(get("/api/exports", Map.of("Cookie", SessionKeys.cookieName(server.port()) + "=wrong")).status())
                .isEqualTo(401);
        assertThat(get("/api/health", Map.of("Cookie", "atlas_session_1=" + keys.secret())).status()).isEqualTo(401);
    }

    @Test
    void aBrowserTabIsToldItCanReloadFromTheAddressTheAppPrinted() throws Exception {
        mode.set(WindowLauncher.Mode.TAB);

        assertThat(get("/", Map.of()).body()).contains("To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window."
                + " Or reload from the address the app printed.");
    }

    @Test
    void aLaunchKeyExchangesOnceForAnHttpOnlyStrictCookieOnAPageThatMovesOnItselfNeverARedirect() throws Exception {
        String key = keys.newKey();

        RawHttp.Response launched = get("/launch?k=" + key, Map.of());

        assertThat(launched.status()).as("a redirect would lose the strict cookie after a file: page").isEqualTo(200);
        assertThat(launched.headers()).doesNotContainKey("location");
        assertThat(launched.headers().get("content-type")).startsWith("text/html");
        assertThat(launched.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(launched.headers().get("referrer-policy")).isEqualTo("no-referrer");
        assertThat(launched.body()).contains("location.replace(\"/\")")
                .contains("<noscript><meta http-equiv=\"refresh\" content=\"0;url=/\"></noscript>");
        assertThat(launched.headers().get("set-cookie")).startsWith(SessionKeys.cookieName(server.port()) + "=" + keys.secret())
                .contains("HttpOnly").contains("Path=/").containsIgnoringCase("SameSite=Strict")
                .doesNotContainIgnoringCase("Max-Age").doesNotContainIgnoringCase("Expires");
        assertThat(get("/api/health", cookie()).status()).isEqualTo(200);
        assertThat(get("/", cookie()).status()).isEqualTo(200);

        RawHttp.Response again = get("/launch?k=" + key, Map.of());
        assertThat(again.status()).isEqualTo(401);
        assertThat(again.body()).contains("To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.");
        assertThat(again.headers()).doesNotContainKey("set-cookie");
    }

    @Test
    void aSecondWindowDoesNotInvalidateTheFirst() throws Exception {
        String first = get("/launch?k=" + keys.newKey(), Map.of()).headers().get("set-cookie");
        String second = get("/launch?k=" + keys.newKey(), Map.of()).headers().get("set-cookie");

        assertThat(first.split(";")[0]).isEqualTo(second.split(";")[0]);
        assertThat(get("/api/health", cookie()).status()).isEqualTo(200);
    }

    @Test
    void aWrongOrExpiredKeyIsRefusedWithTheSamePage() throws Exception {
        assertThat(get("/launch?k=nope", Map.of()).status()).isEqualTo(401);
        assertThat(get("/launch", Map.of()).status()).isEqualTo(401);
        String old = keys.newKey();
        clock.advance(Duration.ofSeconds(61));

        RawHttp.Response expired = get("/launch?k=" + old, Map.of());

        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.body()).contains("To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.");
    }

    @Test
    void aNoticeInTheLaunchAddressIsNoLongerReadOrPassedOn() throws Exception {
        RawHttp.Response noticed = get("/launch?k=" + keys.newKey() + "&notice=already-running", Map.of());

        assertThat(noticed.status()).isEqualTo(200);
        assertThat(noticed.body()).doesNotContain("notice");
    }

    @Test
    void theHostCheckStillRunsFirst() throws Exception {
        RawHttp.Response response = get("/api/health", Map.of("Host", "evil.example", "Cookie",
                SessionKeys.cookieName(server.port()) + "=" + keys.secret()));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("forbidden_host");
        assertThat(get("/launch?k=" + keys.newKey(), Map.of("Host", "evil.example")).status()).isEqualTo(403);
    }

    @Test
    void aSecondLaunchAsksForAKeyWithThePointerFilesKey() throws Exception {
        RawHttp.Response wrong = RawHttp.send(server.port(), "POST", "/launch/new", Map.of("X-Atlas-Key", "nope"), null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body()).contains("\"code\":\"no_session\"");
        assertThat(RawHttp.send(server.port(), "POST", "/launch/new", Map.of(), null).status()).isEqualTo(401);

        RawHttp.Response given = RawHttp.send(server.port(), "POST", "/launch/new",
                Map.of("X-Atlas-Key", keys.instanceKey()), null); // no Origin, no body
        assertThat(given.status()).isEqualTo(200);
        String launch = JSON.readTree(given.body()).get("launch").asText();
        assertThat(get("/launch?k=" + launch, Map.of()).status()).isEqualTo(200);

        assertThat(RawHttp.send(server.port(), "POST", "/launch/new",
                Map.of("X-Atlas-Key", keys.instanceKey(), "Host", "evil.example"), null).status()).isEqualTo(403);
    }

    @Test
    void whileTheAppIsQuittingASecondLaunchIsToldItIsClosing() throws Exception {
        closing.set(true);

        RawHttp.Response response = RawHttp.send(server.port(), "POST", "/launch/new",
                Map.of("X-Atlas-Key", keys.instanceKey()), null);

        assertThat(response.status()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText()).isEqualTo("closing");
    }

    private RawHttp.Response newWindow(Map<String, String> headers) throws Exception {
        Map<String, String> all = new java.util.HashMap<>(Map.of("Origin", "http://127.0.0.1:" + server.port(),
                "Content-Type", "application/json"));
        all.putAll(headers);
        return RawHttp.send(server.port(), "POST", "/api/window", all, "{}");
    }

    @Test
    void newWindowOpensOneMoreWindowAndAnswers204() throws Exception {
        RawHttp.Response opened = newWindow(cookie());

        assertThat(opened.status()).isEqualTo(204);
        assertThat(windowsAsked.get()).isEqualTo(1);
    }

    @Test
    void aNewWindowThatCouldNotBeOpenedIsWindowFailedInWordsAndTheAppGoesOn() throws Exception {
        windowOpens.set(false);

        RawHttp.Response failed = newWindow(cookie());

        assertThat(failed.status()).isEqualTo(500);
        JsonNode error = JSON.readTree(failed.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("window_failed");
        assertThat(error.get("message").asText())
                .isEqualTo("Butterfly Atlas couldn't open another window. Open it from its icon instead.");
        assertThat(quits).isEmpty();
        assertThat(get("/api/health", cookie()).status()).isEqualTo(200);
    }

    @Test
    void newWindowNeedsTheCookieAndTheAppsOwnOrigin() throws Exception {
        assertThat(newWindow(Map.of()).status()).isEqualTo(401);
        Map<String, String> otherSite = new java.util.HashMap<>(cookie());
        otherSite.put("Origin", "http://evil.example");
        assertThat(newWindow(otherSite).status()).isEqualTo(403);
        assertThat(windowsAsked.get()).isZero();
    }

    @Test
    void theSessionAnswersWhatAboutShows() throws Exception {
        JsonNode window = JSON.readTree(get("/api/session", cookie()).body());
        assertThat(window.get("mode").asText()).isEqualTo("window");
        assertThat(window.get("version").asText()).isEqualTo(AtlasVersion.current());
        assertThat(window.get("dataFolder").isNull()).isTrue(); // no library chosen yet
        assertThat(window.get("configFolder").asText()).isEqualTo(home.resolve("config").toString());
        assertThat(window.get("logFolder").asText()).isEqualTo(home.resolve("config").resolve("logs").toString());
        assertThat(window.get("lockWarning").asBoolean()).isFalse();

        mode.set(WindowLauncher.Mode.TAB);
        assertThat(JSON.readTree(get("/api/session", cookie()).body()).get("mode").asText()).isEqualTo("tab");
        assertThat(get("/api/session", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void theDataFolderIsTheOpenLibrary() throws Exception {
        services.setup().setup(home.resolve("Atlas").toString());

        JsonNode session = JSON.readTree(get("/api/session", cookie()).body());

        assertThat(session.get("dataFolder").asText()).isEqualTo(home.resolve("Atlas").toString());
    }

    /** Opens the stream with a socket and returns it with a reader on what the server sends. */
    private Socket openStream(Map<String, String> headers) throws Exception {
        Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), server.port());
        socket.setSoTimeout(5000);
        StringBuilder request = new StringBuilder("GET /api/session/stream HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                + "\r\nAccept: text/event-stream\r\n");
        headers.forEach((k, v) -> request.append(k).append(": ").append(v).append("\r\n"));
        socket.getOutputStream().write((request + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        return socket;
    }

    private static void awaitStreams(QuitTimer quit, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (quit.streams() != count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(quit.streams()).isEqualTo(count);
    }

    @Test
    void theStreamAnswersAtOnceWithAnOpenCommentThenPingsAndCountsWhileItIsOpen() throws Exception {
        try (Socket socket = openStream(cookie())) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = in.readLine();
            assertThat(status).contains("200");
            List<String> seen = new ArrayList<>();
            while (seen.stream().filter(l -> l.equals(": ping")).count() < 2) {
                String line = in.readLine();
                assertThat(line).isNotNull();
                seen.add(line);
            }
            assertThat(seen).contains(": open", ": ping");
            assertThat(seen.indexOf(": open")).isLessThan(seen.indexOf(": ping"));
            assertThat(seen.stream().filter(l -> l.toLowerCase().startsWith("content-type")).findFirst().orElse(""))
                    .contains("text/event-stream");
            assertThat(quit.streams()).isEqualTo(1);
        }
        awaitStreams(quit, 0); // the window closed: a failed ping write ends the stream
    }

    /** Reads lines until {@code wanted} has been seen {@code times} times; the lines read. */
    private static List<String> readUntil(BufferedReader in, String wanted, int times) throws Exception {
        List<String> seen = new ArrayList<>();
        while (seen.stream().filter(wanted::equals).count() < times) {
            String line = in.readLine();
            assertThat(line).as("the stream ended before " + wanted).isNotNull();
            seen.add(line);
        }
        return seen;
    }

    @Test
    void theStreamTellsTheWindowOfTheStatusAsItOpensAndWheneverTheStatusChanges() throws Exception {
        try (Socket socket = openStream(cookie())) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            List<String> first = readUntil(in, "event: status", 1);
            assertThat(first).contains(": open");
            assertThat(in.readLine()).isEqualTo("data: {}");
            assertThat(in.readLine()).isEmpty();
            assertThat(first.indexOf(": open")).isLessThan(first.indexOf("event: status"));

            List<String> quiet = readUntil(in, ": ping", 25); // some 2.5 seconds with nothing changed
            assertThat(quiet).doesNotContain("event: status");

            services.setup().setup(home.resolve("Atlas").toString()); // a library opens: the status changes
            long began = System.nanoTime();
            readUntil(in, "event: status", 1);
            assertThat(System.nanoTime() - began).as("within about a second").isLessThan(3_000_000_000L);
        }
    }

    @Test
    void aClosedWindowIsNoticedWithinAFewSecondsAtTheDefaultPing() throws Exception {
        server.close();
        server = AtlasServer.start(0, services, new Session(keys, quit, mode::get, closing::get, home.resolve("config"),
                home.resolve("config").resolve("logs")));
        long began = System.nanoTime();
        try (Socket socket = openStream(cookie())) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = in.readLine();
            } while (!": open".equals(line));
            assertThat(quit.streams()).isEqualTo(1);
        }
        awaitStreams(quit, 0);
        assertThat(System.nanoTime() - began).isLessThan(6_000_000_000L);
    }

    @Test
    void aStreamWithoutTheCookieIsRefusedAndNeverCounted() throws Exception {
        try (Socket socket = openStream(Map.of())) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertThat(in.readLine()).contains("401");
        }
        assertThat(quit.streams()).isZero();
    }

    @Test
    void theRegisteredRoutesAreListed() {
        assertThat(server.routes()).contains("GET /api/health", "GET /launch", "POST /launch/new", "GET /api/session",
                "GET /api/session/stream", "POST /api/window", "GET /api/status", "POST /api/exports", "GET /api/exports/{id}")
                .isSorted();
    }
}
