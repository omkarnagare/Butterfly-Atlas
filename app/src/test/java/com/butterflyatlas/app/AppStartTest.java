package com.butterflyatlas.app;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.FakeProcess;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.BrowserFinder;
import com.butterflyatlas.app.window.InstanceLock;
import com.butterflyatlas.app.window.InstancePointer;
import com.butterflyatlas.app.window.SecondLaunch;
import com.butterflyatlas.app.window.StartupFailure;
import com.butterflyatlas.app.window.WindowLauncher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The start of the app run for real in this process (everything outside it, from the exit to the browser program, is
 * replaced): the secret is installed, the second launch, the port, the failures that are shown, the quit.
 */
class AppStartTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private Path config;
    private AtlasLog log;
    private final List<Integer> exits = new CopyOnWriteArrayList<>();
    private final List<List<String>> started = new CopyOnWriteArrayList<>();
    private final List<URI> pages = new CopyOnWriteArrayList<>();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final List<AppStart.Running> apps = new ArrayList<>();
    private int defaultPort = freePort();
    private Duration lockWait = Duration.ZERO;
    private boolean browserFound = true;

    @BeforeEach
    void folders() throws Exception {
        config = Files.createDirectories(home.resolve("config"));
        log = AtlasLog.open(config);
    }

    @AfterEach
    void quit() {
        apps.forEach(AppStart.Running::close);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AppOptions options(boolean noBrowser, Integer port, Optional<Path> dataDir) {
        return new AppOptions(noBrowser, port, dataDir, config, 1, 60, Optional.empty(), Optional.empty());
    }

    private AppStart.World world() {
        return new AppStart.World(exits::add, hook -> { }, uri -> pages.add(uri), () -> new BrowserFinder.Found(
                browserFound ? Optional.of(Path.of("/fake/chrome")) : Optional.empty(), null), command -> {
            started.add(command);
            return new FakeProcess();
        }, SecondLaunch::system, lockWait, defaultPort, console::add);
    }

    private AppStart.Running start(AppOptions options) {
        AppStart start = new AppStart(options, log, world());
        start.browserWait = Duration.ofMillis(200); // a fake browser that never exits is not waited for 5 seconds
        AppStart.Running app = start.run();
        if (app != null) {
            apps.add(app);
        }
        return app;
    }

    private static RawHttp.Response get(AppStart.Running app, String path, Map<String, String> headers) throws Exception {
        return RawHttp.send(app.server().port(), "GET", path, headers, null);
    }

    /** The cookie a window gets: the pointer file's key asks for a launch key, which is exchanged. */
    private String cookie(AppStart.Running app) throws Exception {
        InstancePointer.Info info = new InstancePointer(config).read().orElseThrow();
        RawHttp.Response launch = RawHttp.send(info.port(), "POST", "/launch/new", Map.of("X-Atlas-Key", info.key()), null);
        assertThat(launch.status()).isEqualTo(200);
        RawHttp.Response exchanged = get(app, "/launch?k=" + JSON.readTree(launch.body()).get("launch").asText(), Map.of());
        assertThat(exchanged.status()).isEqualTo(200);
        return exchanged.headers().get("set-cookie").split(";")[0];
    }

    /** The launch address (with its key) a redirect file sends the window to: from the command's {@code --app=file:…}. */
    private static String redirectTarget(String appArgument) throws Exception {
        assertThat(appArgument).startsWith("--app=file:");
        String page = Files.readString(Path.of(URI.create(appArgument.substring("--app=".length()))));
        java.util.regex.Matcher target = java.util.regex.Pattern.compile("content=\"0;url=([^\"]+)\"").matcher(page);
        assertThat(target.find()).isTrue();
        return target.group(1).replace("&amp;", "&");
    }

    private String page() throws Exception {
        return Files.readString(config.resolve("startup_problem.html"));
    }

    @Test
    void theStartInstallsTheSessionSoNothingIsAnsweredWithoutItsCookie() throws Exception {
        AppStart.Running app = start(options(true, 0, Optional.empty()));

        assertThat(app).isNotNull();
        assertThat(exits).isEmpty();
        RawHttp.Response refused = get(app, "/api/health", Map.of());
        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.body()).contains("\"code\":\"no_session\"");
        assertThat(get(app, "/", Map.of()).status()).isEqualTo(401);
        InstancePointer.Info info = new InstancePointer(config).read().orElseThrow();
        assertThat(info.port()).isEqualTo(app.server().port());
        assertThat(info.pid()).isEqualTo(ProcessHandle.current().pid());

        String cookie = cookie(app);

        assertThat(get(app, "/api/health", Map.of("Cookie", cookie)).status()).isEqualTo(200);
        var session = JSON.readTree(get(app, "/api/session", Map.of("Cookie", cookie)).body());
        assertThat(session.get("configFolder").asText()).isEqualTo(config.toString());
        assertThat(session.get("logFolder").asText()).isEqualTo(config.resolve("logs").toString());
        assertThat(session.get("mode").asText()).isEqualTo("window");
        assertThat(started).isEmpty(); // -Datlas.noBrowser=true opens nothing
        assertThat(pages).isEmpty();
        String printed = console.stream().filter(line -> line.startsWith("Open http://127.0.0.1:")).findFirst().orElseThrow();
        String address = printed.substring("Open ".length(), printed.indexOf(" in a browser"));
        assertThat(get(app, address.substring(address.indexOf("/launch")), Map.of()).status()).isEqualTo(200);
        assertThat(Files.readString(log.file())).doesNotContain(address.substring(address.indexOf("k=") + 2));
    }

    @Test
    void aQuitRemovesThePointerAndAFreshStartFindsNothingRunning() throws Exception {
        AppStart.Running app = start(options(true, 0, Optional.empty()));
        assertThat(config.resolve("instance.json")).exists();

        app.close();
        apps.clear();

        assertThat(config.resolve("instance.json")).doesNotExist();
    }

    @Test
    void theWindowOpensOnALaunchKeyThatNeverReachesTheLog() throws Exception {
        AppStart.Running app = start(options(false, 0, Optional.empty()));

        assertThat(started).hasSize(1);
        List<String> command = started.get(0);
        assertThat(command.get(0)).isEqualTo(Path.of("/fake/chrome").toString());
        assertThat(command).contains("--user-data-dir=" + config.resolve("browser_profile"), "--no-first-run",
                "--no-default-browser-check");
        assertThat(String.join(" ", command)).doesNotContain("k=");
        String address = redirectTarget(command.get(1));
        assertThat(address).startsWith("http://127.0.0.1:" + app.server().port() + "/launch?k=");
        String key = address.substring(address.indexOf("k=") + 2);
        assertThat(String.join(" ", command)).doesNotContain(key);
        try (var folder = Files.list(config.resolve("launch"))) {
            assertThat(folder.count()).isEqualTo(1);
        }
        assertThat(get(app, "/launch?k=" + key, Map.of()).status()).isEqualTo(200);
        try (var folder = Files.list(config.resolve("launch"))) {
            assertThat(folder.count()).as("deleted once the key is used").isZero();
        }
        assertThat(Files.readString(log.file())).contains("is running on port " + app.server().port()).doesNotContain(key);
        assertThat(console.toString()).doesNotContain(key);
    }

    @Test
    void theLaunchFolderIsEmptiedAtTheStartAndAtTheQuitSoALeftoverNeverHoldsALiveKey() throws Exception {
        Files.createDirectories(config.resolve("launch"));
        Files.writeString(config.resolve("launch/open_00.html"), "left by a crash");

        AppStart.Running app = start(options(true, 0, Optional.empty()));
        assertThat(config.resolve("launch/open_00.html")).doesNotExist();
        Files.writeString(config.resolve("launch/open_01.html"), "written while running");
        app.close();
        apps.clear();

        assertThat(config.resolve("launch/open_01.html")).doesNotExist();
    }

    @Test
    void aWindowThatCannotBeOpenedBecauseTheStartupFileCannotBeWrittenIsShownAndNeverPutsAKeyInTheCommand() throws Exception {
        Files.writeString(config.resolve("launch"), "a file where the folder should be");

        AppStart.Running app = start(options(false, 0, Optional.empty()));
        long deadline = System.currentTimeMillis() + 10_000;
        while (exits.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(app).isNotNull();
        assertThat(started).isEmpty();
        assertThat(pages).containsExactly(config.resolve("startup_problem.html").toUri());
        assertThat(page()).contains("could not write the small file that opens its window");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void withNoBrowserTheDefaultBrowserOpensATabAndTheSessionSaysTab() throws Exception {
        browserFound = false;

        AppStart.Running app = start(options(false, 0, Optional.empty()));

        assertThat(started).isEmpty();
        assertThat(pages).singleElement().satisfies(uri -> assertThat(uri.getScheme()).isEqualTo("file"));
        assertThat(redirectTarget("--app=" + pages.get(0))).startsWith("http://127.0.0.1:" + app.server().port() + "/launch?k=");
        String cookie = cookie(app);
        assertThat(get(app, "/api/session", Map.of("Cookie", cookie)).body()).contains("\"mode\":\"tab\"");
    }

    private static RawHttp.Response newWindow(AppStart.Running app, String cookie) throws Exception {
        return RawHttp.send(app.server().port(), "POST", "/api/window", Map.of("Cookie", cookie, "Origin",
                "http://127.0.0.1:" + app.server().port(), "Content-Type", "application/json"), "{}");
    }

    @Test
    void newWindowOpensOneMoreAppWindowOnAFreshKeyAsASecondLaunchDoes() throws Exception {
        AppStart.Running app = start(options(false, 0, Optional.empty()));
        String first = redirectTarget(started.get(0).get(1));
        String cookie = cookie(app);

        RawHttp.Response opened = newWindow(app, cookie);

        assertThat(opened.status()).isEqualTo(204);
        assertThat(started).hasSize(2);
        assertThat(String.join(" ", started.get(1))).doesNotContain("k=");
        String address = redirectTarget(started.get(1).get(1));
        assertThat(address).startsWith("http://127.0.0.1:" + app.server().port() + "/launch?k=").isNotEqualTo(first)
                .doesNotContain("notice");
        assertThat(get(app, address.substring(address.indexOf("/launch")), Map.of()).status()).isEqualTo(200);
        assertThat(exits).isEmpty();
    }

    @Test
    void newWindowAnswersAsSoonAsTheNewWindowsPageConnectsNotAfterTheWholeWait() throws Exception {
        AppStart.World connecting = new AppStart.World(exits::add, hook -> { }, uri -> pages.add(uri),
                () -> new BrowserFinder.Found(Optional.of(Path.of("/fake/chrome")), null), command -> {
            started.add(command);
            return new FakeProcess(); // a browser that keeps running: it shows its window
        }, SecondLaunch::system, lockWait, defaultPort, console::add);
        AppStart start = new AppStart(options(false, 0, Optional.empty()), log, connecting);
        start.browserWait = Duration.ofSeconds(5);
        AppStart.Running app = start.run();
        apps.add(app);
        String cookie = cookie(app);
        Thread page = new Thread(() -> {
            try {
                while (started.size() < 2) {
                    Thread.sleep(10);
                }
                // the new window's page connects its stream
                try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), app.server().port())) {
                    socket.getOutputStream().write(("GET /api/session/stream HTTP/1.1\r\nHost: 127.0.0.1:"
                            + app.server().port() + "\r\nAccept: text/event-stream\r\nCookie: " + cookie + "\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getInputStream().read();
                    Thread.sleep(3000);
                }
            } catch (Exception e) {
                // the test ends meanwhile
            }
        });
        page.setDaemon(true);
        page.start();

        long began = System.nanoTime();
        RawHttp.Response opened = newWindow(app, cookie);

        assertThat(opened.status()).isEqualTo(204);
        assertThat(System.nanoTime() - began).as("not the whole five seconds").isLessThan(3_000_000_000L);
    }

    @Test
    void aNewWindowThatCannotBeOpenedIsWindowFailedAndNeverAStartupPageOrAQuit() throws Exception {
        AppStart.World failingLater = new AppStart.World(exits::add, hook -> { }, uri -> {
            pages.add(uri);
            return false; // no default browser either
        }, () -> new BrowserFinder.Found(Optional.of(Path.of("/fake/chrome")), null), command -> {
            started.add(command);
            if (started.size() > 1) {
                throw new java.io.IOException("Permission denied");
            }
            return new FakeProcess();
        }, SecondLaunch::system, lockWait, defaultPort, console::add);
        AppStart start = new AppStart(options(false, 0, Optional.empty()), log, failingLater);
        start.browserWait = Duration.ofMillis(200);
        AppStart.Running app = start.run();
        apps.add(app);

        RawHttp.Response failed = newWindow(app, cookie(app));

        assertThat(failed.status()).isEqualTo(500);
        assertThat(JSON.readTree(failed.body()).at("/error/code").asText()).isEqualTo("window_failed");
        assertThat(exits).isEmpty();
        assertThat(config.resolve("startup_problem.html")).doesNotExist();
        assertThat(Files.readString(log.file())).contains("New window: no window or tab could be opened.");
        assertThat(get(app, "/api/health", Map.of("Cookie", cookie(app))).status()).isEqualTo(200);
    }

    @Test
    void theFixedPortIsTriedFirstAndAnyFreePortWhenItIsTaken() throws Exception {
        AppStart.Running onFixed = start(options(true, null, Optional.empty()));
        assertThat(onFixed.server().port()).isEqualTo(defaultPort);
        onFixed.close();
        apps.clear();

        try (ServerSocket taken = new ServerSocket(defaultPort, 1, InetAddress.getByName("127.0.0.1"))) {
            AppStart.Running other = start(options(true, null, Optional.empty()));

            assertThat(other).isNotNull();
            assertThat(other.server().port()).isNotEqualTo(defaultPort).isNotEqualTo(taken.getLocalPort());
            assertThat(Files.readString(log.file())).contains("Port " + defaultPort + " is taken");
            assertThat(exits).isEmpty();
        }
    }

    @Test
    void aPortNamedInTheSettingsThatIsTakenIsShownLoggedAndExitsWithOne() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = taken.getLocalPort();

            AppStart.Running app = start(options(true, port, Optional.empty()));

            assertThat(app).isNull();
            assertThat(exits).containsExactly(1);
            assertThat(page()).contains("Another program is using port " + port);
            assertThat(pages).containsExactly(config.resolve("startup_problem.html").toUri());
            assertThat(Files.readString(log.file())).contains("Could not start on port " + port);
            assertThat(config.resolve("instance.json")).doesNotExist();
        }
    }

    @Test
    void aSecondLaunchOpensAWindowOnTheRunningCopyWithoutANoticeAndStartsNothing() throws Exception {
        AppStart.Running first = start(options(true, 0, Optional.empty()));
        InstancePointer.Info before = new InstancePointer(config).read().orElseThrow();

        AppStart.Running second = start(options(false, 0, Optional.empty()));

        assertThat(second).isNull();
        assertThat(exits).containsExactly(0);
        assertThat(started).hasSize(1);
        assertThat(String.join(" ", started.get(0))).doesNotContain("k=");
        String address = redirectTarget(started.get(0).get(1));
        assertThat(address).startsWith("http://127.0.0.1:" + first.server().port() + "/launch?k=").doesNotContain("notice");
        assertThat(new InstancePointer(config).read()).contains(before); // the running copy's pointer is untouched
        RawHttp.Response launched = get(first, address.substring(address.indexOf("/launch")), Map.of());
        assertThat(launched.status()).isEqualTo(200);
        assertThat(launched.body()).doesNotContain("notice");
    }

    @Test
    void aSecondLaunchWhoseRunningCopyDoesNotAnswerShowsTheMessagePageAndExitsWithTwo() throws Exception {
        new InstancePointer(config).write(InstancePointer.Info.ofThisProcess(freePort(), "key"));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AppStart.World ticking = new AppStart.World(exits::add, hook -> { }, uri -> pages.add(uri),
                () -> new BrowserFinder.Found(Optional.empty(), null), command -> new FakeProcess(),
                pointer -> new SecondLaunch(pointer, (port, key) -> {
                    throw new java.io.IOException("refused");
                }, clock::get, millis -> clock.addAndGet(millis * 1_000_000L)), lockWait, defaultPort, console::add);

        AppStart.Running app = new AppStart(options(true, 0, Optional.empty()), log, ticking).run();

        assertThat(app).isNull();
        assertThat(exits).containsExactly(2);
        assertThat(page()).contains("already running").contains("not answering");
        assertThat(pages).containsExactly(config.resolve("startup_problem.html").toUri());
    }

    private AppStart.World second(java.util.function.LongConsumer onSleep, java.util.concurrent.atomic.AtomicLong clock) {
        return new AppStart.World(exits::add, hook -> { }, uri -> pages.add(uri), () -> new BrowserFinder.Found(
                Optional.of(Path.of("/fake/chrome")), null), command -> {
            started.add(command);
            return new FakeProcess();
        }, pointer -> new SecondLaunch(pointer, (port, key) -> new SecondLaunch.Reply(200, "{\"launch\":\"abc\"}"),
                clock::get, millis -> {
                    clock.addAndGet(millis * 1_000_000L);
                    onSleep.accept(millis);
                }), lockWait, defaultPort, console::add);
    }

    /** A second launch against a running copy that answers, with this browser program and this default browser. */
    private AppStart.Running secondLaunch(WindowLauncher.Starter starter, java.util.function.Predicate<URI> defaultBrowser)
            throws Exception {
        new InstancePointer(config).write(InstancePointer.Info.ofThisProcess(freePort(), "their-key"));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AppStart.World world = new AppStart.World(exits::add, hook -> { }, defaultBrowser,
                () -> new BrowserFinder.Found(Optional.of(Path.of("/fake/chrome")), null), starter,
                pointer -> new SecondLaunch(pointer, (port, key) -> new SecondLaunch.Reply(200, "{\"launch\":\"abc\"}"),
                        clock::get, millis -> clock.addAndGet(millis * 1_000_000L)), lockWait, defaultPort, console::add);
        return new AppStart(options(false, 0, Optional.empty()), log, world).run();
    }

    @Test
    void aSecondLaunchWhoseBrowserFailsAfterAWhileFallsBackToTheDefaultBrowserBeforeThisLaunchExits() throws Exception {
        List<Long> exitedAt = new CopyOnWriteArrayList<>();
        AppStart.Running app = secondLaunch(command -> {
            started.add(command);
            FakeProcess failing = new FakeProcess();
            Thread late = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                failing.exits(1);
            });
            late.start();
            return failing;
        }, uri -> {
            pages.add(uri);
            return true;
        });

        assertThat(app).isNull();
        assertThat(started).hasSize(1);
        assertThat(pages).as("the fallback ran before the launch ended").hasSize(1);
        assertThat(pages.get(0).getScheme()).isEqualTo("file");
        assertThat(redirectTarget("--app=" + pages.get(0))).contains("/launch?k=abc");
        assertThat(exits).containsExactly(0);
    }

    @Test
    void aSecondLaunchWhoseBrowserAndDefaultBrowserBothFailShowsTheMessageInWordsAndExitsWithOne() throws Exception {
        AppStart.Running app = secondLaunch(command -> {
            throw new java.io.IOException("Permission denied");
        }, uri -> false);

        assertThat(app).isNull();
        assertThat(exits).containsExactly(1);
        String words = "Butterfly Atlas is running, but couldn't open another window. Use one of its open windows, or use"
                + " New window there.";
        assertThat(console.toString()).contains(words);
        assertThat(page()).contains("Butterfly Atlas is running, but couldn't open another window.")
                .contains("Use one of its open windows, or use New window there.")
                .doesNotContain("Only one copy can run at a time");
    }

    @Test
    void aFastSecondLaunchWhileTheFirstIsStillOpeningItsLibraryWaitsForItsPointerAndOpensAWindowOnIt() throws Exception {
        InstanceLock.Result starting = InstanceLock.acquireFile(config.resolve("instance.lock")); // the first copy, not yet up
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        int[] sleeps = {0};
        InstancePointer.Info theirs = InstancePointer.Info.ofThisProcess(freePort(), "their-key");
        try {
            AppStart.Running app = new AppStart(options(false, 0, Optional.empty()), log,
                    second(millis -> {
                        if (++sleeps[0] == 3) {
                            new InstancePointer(config).write(theirs);
                        }
                    }, clock)).run();

            assertThat(app).as("no second server").isNull();
            assertThat(exits).containsExactly(0);
            assertThat(started).hasSize(1);
            assertThat(new InstancePointer(config).read()).as("the first copy's pointer is not overwritten").contains(theirs);
            assertThat(clock.get()).as("waited a few quarter seconds, not thirty seconds").isLessThan(5_000_000_000L);
        } finally {
            starting.lock().close();
        }
    }

    @Test
    void aSecondLaunchWhoseFirstCopyNeverWritesItsPointerShowsTheMessagePageAfterThirtySecondsAndExitsWithTwo()
            throws Exception {
        InstanceLock.Result starting = InstanceLock.acquireFile(config.resolve("instance.lock"));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        try {
            AppStart.Running app = new AppStart(options(true, 0, Optional.empty()), log, second(millis -> { }, clock)).run();

            assertThat(app).isNull();
            assertThat(exits).containsExactly(2);
            assertThat(page()).contains("already running");
            assertThat(clock.get()).isGreaterThanOrEqualTo(30_000_000_000L);
            assertThat(config.resolve("instance.json")).doesNotExist();
            assertThat(started).isEmpty();
        } finally {
            starting.lock().close();
        }
    }

    @Test
    void aSavedLibraryAnotherCopyStillHoldsAfterTheWaitIsTheMessagePageNotASetupProblemAndNoServerStarts() throws Exception {
        Path library = Files.createDirectories(home.resolve("Atlas"));
        new com.butterflyatlas.app.config.ConfigStore(config).save(new com.butterflyatlas.app.config.AppConfig(library.toString()));
        InstanceLock.Result other = InstanceLock.acquire(library);
        try {
            AppStart.Running app = start(options(true, 0, Optional.empty()));

            assertThat(app).isNull();
            assertThat(exits).containsExactly(2);
            assertThat(page()).contains("already running").contains(library.toString());
            assertThat(config.resolve("instance.json")).doesNotExist();
            assertThat(Files.readString(log.file())).doesNotContain("is running on port");
        } finally {
            other.lock().close();
        }
    }

    @Test
    void theInstanceLockIsLetGoWhenAQuitStartsSoALaterLaunchIsNotTurnedAway() throws Exception {
        AppStart.Running app = start(options(true, 0, Optional.empty()));
        assertThat(InstanceLock.acquireFile(config.resolve("instance.lock")).state()).isEqualTo(InstanceLock.State.HELD);

        app.close();
        apps.clear();

        InstanceLock.Result later = InstanceLock.acquireFile(config.resolve("instance.lock"));
        assertThat(later.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        later.lock().close();
    }

    private void newerLibrary(Path folder) throws Exception {
        Database db = Database.open(Files.createDirectories(folder).resolve(Database.FILE_NAME));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO flyway_schema_history (installed_rank, version,"
                    + " description, type, script, checksum, installed_by, execution_time, success) VALUES"
                    + " ((SELECT MAX(installed_rank) + 1 FROM flyway_schema_history), '99', 'newer', 'SQL', 'V99__x.sql', 0,"
                    + " 'x', 0, 1)")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void aLibraryFromANewerVersionIsShownInPlainWordsAndExitsWithOne() throws Exception {
        Path library = home.resolve("Atlas");
        newerLibrary(library);

        AppStart.Running app = start(options(true, 0, Optional.of(library)));

        assertThat(app).isNull();
        assertThat(exits).containsExactly(1);
        assertThat(page()).contains("This library was made by a newer Butterfly Atlas (database version 99;")
                .contains("Install the newer version");
        assertThat(Files.readString(log.file())).contains("newer than this app");
    }

    @Test
    void aLibraryAnotherCopyUsesIsTheAlreadyRunningMessage() throws Exception {
        Path library = Files.createDirectories(home.resolve("Atlas"));
        InstanceLock.Result other = InstanceLock.acquire(library);
        try {
            AppStart.Running app = start(options(true, 0, Optional.of(library)));

            assertThat(app).isNull();
            assertThat(exits).containsExactly(1);
            assertThat(page()).contains("Butterfly Atlas is already running. Only one copy can run at a time.")
                    .contains(library.toString());
        } finally {
            other.lock().close();
        }
    }

    @Test
    void aLibraryThatCannotBeReadIsShownWithAPlainReasonNeverTheRawError() throws Exception {
        Path library = Files.createDirectories(home.resolve("Atlas"));
        Files.writeString(library.resolve(Database.FILE_NAME), "this is not a database, just text in the way ".repeat(50));

        AppStart.Running app = start(options(true, 0, Optional.of(library)));

        assertThat(app).isNull();
        assertThat(exits).containsExactly(1);
        assertThat(page()).contains("Butterfly Atlas could not open your library at " + library)
                .doesNotContain("Exception").doesNotContain("org.");
    }

    @Test
    void aDamagedBundleIsShownAndExitsWithOne() throws Exception {
        Path empty = Files.createDirectories(home.resolve("no-bundles"));

        AppStart.Running app = start(new AppOptions(true, 0, Optional.empty(), config, 1, 60, Optional.of(empty),
                Optional.empty()));

        assertThat(app).isNull();
        assertThat(exits).containsExactly(1);
        assertThat(page()).contains("Butterfly Atlas's own data is damaged").contains("Install Butterfly Atlas again");
    }

    @Test
    void aConfigFolderThatCannotBeWrittenIsShownOnTheConsoleAndExitsWithOne() throws Exception {
        Path blocked = Files.writeString(home.resolve("not_a_folder"), "a file where the config folder should be");
        AppOptions options = new AppOptions(true, 0, Optional.empty(), blocked.resolve("config"), 1, 60, Optional.empty(),
                Optional.empty());

        assertThat(new AppStart(options, log, world()).run()).isNull();

        assertThat(exits).containsExactly(1);
        assertThat(console.toString()).contains("cannot save its settings in " + blocked.resolve("config"));
        assertThat(Files.readString(log.file())).contains("Could not write the pointer file");
        assertThat(pages).as("shown on a page in the temp folder, not only on the console").singleElement().satisfies(uri -> {
            Path page = Path.of(uri);
            try {
                assertThat(page.getFileName().toString()).startsWith("butterfly_atlas_problem_");
                assertThat(Files.readString(page)).contains("cannot save its settings in " + blocked.resolve("config"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } finally {
                page.toFile().delete();
            }
        });
    }

    @Test
    void anUnexpectedErrorIsShownWithoutAClassNameAndTheTraceGoesToTheLog() throws Exception {
        AppStart.World broken = new AppStart.World(exits::add, hook -> { }, uri -> pages.add(uri),
                () -> new BrowserFinder.Found(Optional.empty(), null), command -> new FakeProcess(), pointer -> {
            throw new IllegalStateException("boom inside");
        }, lockWait, defaultPort, console::add);

        assertThat(new AppStart(options(true, 0, Optional.empty()), log, broken).run()).isNull();

        assertThat(exits).containsExactly(1);
        assertThat(page()).contains("Butterfly Atlas hit an unexpected problem. The details are in the log file.")
                .doesNotContain("IllegalStateException").doesNotContain("boom");
        assertThat(Files.readString(log.file())).contains("IllegalStateException: boom inside")
                .contains("at com.butterflyatlas.app.AppStart");
    }

    @Test
    void aConfigFolderThatCannotBeWrittenIsShownEvenBeforeTheSettingsAreRead() throws Exception {
        Path blocked = Files.writeString(home.resolve("not_a_folder"), "a file where the config folder should be");

        AppStart.failBeforeStart(log, blocked.resolve("config"), StartupFailure.badSetting("atlas.quitSeconds is wrong"), world());

        assertThat(exits).containsExactly(1);
        assertThat(pages).singleElement().satisfies(uri -> {
            Path page = Path.of(uri);
            try {
                assertThat(Files.readString(page)).contains("atlas.quitSeconds is wrong");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } finally {
                page.toFile().delete();
            }
        });
    }

    @Test
    void settingsThatCannotBeReadAreShownBeforeAnythingStarts() throws Exception {
        AppStart.failBeforeStart(log, config, com.butterflyatlas.app.window.StartupFailure.badSetting(
                "atlas.quitSeconds must be a whole number of seconds, 1 or more, got: 0"), world());

        assertThat(exits).containsExactly(1);
        assertThat(page()).contains("atlas.quitSeconds");
    }

    @Test
    void closingTheLastWindowQuitsAfterTheGraceAndTheSecondLaunchIsToldItIsClosing() throws Exception {
        AppStart.Running app = start(options(true, 0, Optional.empty()));
        String cookie = cookie(app);
        InstancePointer.Info info = new InstancePointer(config).read().orElseThrow();

        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), app.server().port())) {
            socket.getOutputStream().write(("GET /api/session/stream HTTP/1.1\r\nHost: 127.0.0.1:" + app.server().port()
                    + "\r\nAccept: text/event-stream\r\nCookie: " + cookie + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = in.readLine();
                assertThat(line).isNotNull();
            } while (!": open".equals(line));
            Thread.sleep(2500); // one window open: the app stays, however long the grace
            assertThat(exits).isEmpty();
        }

        long deadline = System.currentTimeMillis() + 15_000;
        while (exits.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(exits).containsExactly(0);
        assertThat(config.resolve("instance.json")).as("removed the moment the quit started").doesNotExist();
        RawHttp.Response closing = RawHttp.send(info.port(), "POST", "/launch/new", Map.of("X-Atlas-Key", info.key()), null);
        assertThat(closing.status()).isEqualTo(503);
        assertThat(closing.body()).contains("\"code\":\"closing\"");
        assertThat(Files.readString(log.file())).contains("The last window was closed. Butterfly Atlas is quitting.");
    }

    @Test
    void withNoBrowserTheAppNeverQuitsBeforeAWindowHasConnected() throws Exception {
        start(options(true, 0, Optional.empty()));

        Thread.sleep(3000); // three times the one-second grace

        assertThat(exits).isEmpty();
    }
}
