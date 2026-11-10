package com.butterflyatlas.app.desktop.mac;

import com.butterflyatlas.app.desktop.ProfileHolder;
import com.butterflyatlas.app.testing.WindowChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-window check on macOS (spec §7, contract §5; tag {@value WindowChecks#TAG}, run with {@code -Pwindow-checks}):
 * Chrome or Edge in app mode on a throwaway profile is found from its {@code SingletonLock}; {@link MacNative} activates
 * it by its ID; with its window minimised (through the browser's own DevTools protocol) the window list still has it but
 * shows none on screen, which is when the Dock tile opens a new window instead (the reopen event would make Chrome open a
 * blank tabbed window, since its only window is an app window); then it is asked to quit and exits with code 0 within
 * 10 s. Skipped off macOS.
 */
@Tag(WindowChecks.TAG)
@EnabledOnOs(OS.MAC)
class MacNativeWindowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path folder;

    private final MacNative mac = new MacNative();
    private Process browser;
    private HttpServer server;
    private WebSocket socket;

    @AfterEach
    void closeEverything() throws Exception {
        if (socket != null) {
            socket.abort();
        }
        if (browser != null && browser.isAlive()) {
            browser.destroy();
            if (!browser.waitFor(10, TimeUnit.SECONDS)) {
                browser.destroyForcibly().waitFor(10, TimeUnit.SECONDS); // the test only: the app never force-kills
            }
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve() throws Exception {
        byte[] page = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Window check</title></head>"
                + "<body>A real-window check.</body></html>").getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    /** A minimal DevTools protocol client on the browser's own endpoint (its port is in the profile). */
    private final class DevTools implements WebSocket.Listener {
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();
        private final StringBuilder text = new StringBuilder();

        void connect(Path profile) throws Exception {
            Path active = profile.resolve("DevToolsActivePort");
            assertThat(await(() -> Files.isRegularFile(active), 30_000)).as("DevToolsActivePort").isTrue();
            await(() -> {
                try {
                    return Files.readAllLines(active).size() >= 2;
                } catch (Exception e) {
                    return false;
                }
            }, 10_000);
            List<String> lines = Files.readAllLines(active);
            URI uri = URI.create("ws://127.0.0.1:" + lines.get(0).trim() + lines.get(1).trim());
            socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, this).get(10, TimeUnit.SECONDS);
        }

        JsonNode call(String method, ObjectNode params) throws Exception {
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> answer = new CompletableFuture<>();
            waiting.put(id, answer);
            ObjectNode message = JSON.createObjectNode().put("id", id).put("method", method);
            message.set("params", params == null ? JSON.createObjectNode() : params);
            socket.sendText(JSON.writeValueAsString(message), true).get(10, TimeUnit.SECONDS);
            JsonNode reply = answer.get(10, TimeUnit.SECONDS);
            assertThat(reply.has("error")).as(method + ": " + reply).isFalse();
            return reply.get("result");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    JsonNode message = JSON.readTree(text.toString());
                    if (message.has("id")) {
                        CompletableFuture<JsonNode> answer = waiting.remove(message.get("id").asInt());
                        if (answer != null) {
                            answer.complete(message);
                        }
                    }
                } catch (Exception e) {
                    // an event this client does not read
                }
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        /** The page targets: one per window of an app-mode browser. */
        List<String> pages() throws Exception {
            JsonNode infos = call("Target.getTargets", null).get("targetInfos");
            return java.util.stream.StreamSupport.stream(infos.spliterator(), false)
                    .filter(info -> "page".equals(info.get("type").asText()))
                    .map(info -> info.get("targetId").asText()).toList();
        }

        int windowOf(String target) throws Exception {
            return call("Browser.getWindowForTarget", JSON.createObjectNode().put("targetId", target)).get("windowId").asInt();
        }

        String state(int window) throws Exception {
            return call("Browser.getWindowBounds", JSON.createObjectNode().put("windowId", window)).get("bounds")
                    .get("windowState").asText();
        }

        void restore(int window) throws Exception {
            ObjectNode params = JSON.createObjectNode().put("windowId", window);
            params.putObject("bounds").put("windowState", "normal");
            call("Browser.setWindowBounds", params);
        }

        void minimise(int window) throws Exception {
            ObjectNode params = JSON.createObjectNode().put("windowId", window);
            params.putObject("bounds").put("windowState", "minimized");
            call("Browser.setWindowBounds", params);
        }
    }

    @Test
    void theBrowserIsFoundActivatedSeenMinimisedSeenWithNoWindowAndQuitsWhenAsked() throws Exception {
        Path program = WindowChecks.browser();
        Path profile = folder.resolve("browser_profile");
        String address = serve();
        browser = new ProcessBuilder(program.toString(), "--app=" + address, "--user-data-dir=" + profile,
                "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long pid = browser.pid();

        assertThat(await(() -> ProfileHolder.singletonLockPid(profile).equals(OptionalLong.of(pid)), 30_000))
                .as("SingletonLock names the browser process").isTrue();
        assertThat(await(() -> mac.windows(pid).onScreen() >= 1, 30_000)).as("the app window is on screen").isTrue();
        // exactly one: Chrome's hidden helper windows (33 px strips, never on screen) are not counted
        List<String> seen = new java.util.ArrayList<>();
        seen.add("open: " + mac.describe(pid));
        assertThat(mac.windows(pid)).as("one app window, counted once").isEqualTo(new MacApps.Windows(1, 1));
        List<Long> appWindow = mac.windowIds(pid);

        assertThat(mac.activate(pid)).as("activated by its process ID").isTrue();

        DevTools devTools = new DevTools();
        devTools.connect(profile);
        List<String> pages = devTools.pages();
        assertThat(pages).hasSize(1);
        int window = devTools.windowOf(pages.get(0));
        devTools.minimise(window);
        assertThat(await(() -> mac.windows(pid).onScreen() == 0, 10_000)).as("minimised: none on screen").isTrue();
        seen.add("minimised: " + mac.describe(pid));
        assertThat(mac.windows(pid)).as("a minimised window is still listed").isEqualTo(new MacApps.Windows(1, 0));
        assertThat(devTools.state(window)).isEqualTo("minimized");

        // back on screen, then closed (a window is closed from the screen): Chrome keeps running on macOS, and the app
        // must see that it has no window left
        devTools.restore(window);
        assertThat(await(() -> mac.windows(pid).onScreen() == 1, 10_000)).as("restored: on screen again").isTrue();
        devTools.call("Target.closeTarget", JSON.createObjectNode().put("targetId", pages.get(0)));
        // Chrome 157 drops the closed window from the list, so the app quits at once; Chrome 156 (GitHub's macOS runner)
        // keeps it, off screen and the same in every detail as a minimised one, so the app cannot tell and waits its 10 s.
        // Either is right; anything else left is not.
        boolean gone = await(() -> mac.windows(pid).all() == 0, 10_000);
        if (!gone) {
            seen.add("closed: " + mac.describe(pid));
            seen.add("pages left: " + devTools.pages());
            seen.add("browser: " + devTools.call("Browser.getVersion", null).get("product").asText());
            assertThat(devTools.pages()).as(() -> "the page closed; the browser's windows " + seen).isEmpty();
            assertThat(mac.windows(pid).onScreen()).as(() -> "nothing on screen; the browser's windows " + seen).isZero();
            assertThat(mac.windowIds(pid)).as(() -> "only the closed window is kept; the browser's windows " + seen)
                    .isEqualTo(appWindow);
        }
        assertThat(browser.isAlive()).as("Chrome still runs with no window").isTrue();

        socket.abort();
        socket = null;
        assertThat(mac.terminate(pid)).as("asked to quit").isTrue();
        assertThat(browser.waitFor(10, TimeUnit.SECONDS)).as("quit within 10 s").isTrue();
        assertThat(browser.exitValue()).isZero();
    }

    @Test
    void aProcessThatIsNotAnAppCannotBeActivated() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> mac.activate(ProcessHandle.current().pid() + 1_000_000))).isNotNull();
    }
}
