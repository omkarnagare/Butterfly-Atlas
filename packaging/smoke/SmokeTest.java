import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The packaging smoke test (Phase 6 design section 9 and 15.11): starts the BUILT app (the application image
 * jpackage made) with throwaway config and library folders and drives it over HTTP, the way a window would. Any failure
 * ends the run with "SMOKE TEST FAILED at step: ..." and a non-zero exit code, so the build that ran it fails and says which
 * step. One file, no dependencies, the same on Windows, Linux and macOS:
 *
 * <pre>
 *   java packaging/smoke/SmokeTest.java --app packaging/target/work/image/"Butterfly Atlas.app" --version 1.0.0
 * </pre>
 *
 * Options: {@code --app} the application image folder (the .app on macOS); {@code --version} the version the app must
 * report; {@code --smoke-dir} this folder (default packaging/smoke under the current folder); {@code --work} a short folder for the throwaway data (default: a new folder in the system's temp); {@code --direct}
 * run the bundled runtime's own java on the app's jars instead of the launcher (the default on Windows, whose launcher is
 * not a console program and shows no output); {@code --keep} leave the throwaway folders.
 *
 * <p>The list of routes it must call is {@code routes.txt} next to this file; {@code SmokeRoutesTest} (a normal unit test)
 * keeps that list equal to the server's routes, and this program fails if a listed route was never called.
 */
public final class SmokeTest {

    static final int START_SECONDS = 90;
    /** The design's budget for the app to quit once its last window connection closed (the test sets atlas.quitSeconds=1). */
    static final int QUIT_BUDGET_SECONDS = 15;

    static final Path HERE = Path.of(System.getProperty("user.dir"));

    // ---- the run

    private static String step = "arguments";
    private static final List<App> apps = new CopyOnWriteArrayList<>();
    private static final Set<String> called = new TreeSet<>();
    private static List<String> listed;
    private static Path work;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path appImage = Path.of(options.getOrDefault("app", "")).toAbsolutePath().normalize();
        String version = options.getOrDefault("version", "");
        boolean direct = options.containsKey("direct") || os().equals("windows");
        boolean keep = options.containsKey("keep");
        // Ctrl-C or a kill signal: stop the apps this run started (a failed or interrupted run must not leave them holding the port and the library).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (App app : apps) {
                app.kill();
            }
        }, "smoke-stop-apps"));
        int exitCode = 0;
        Path script = options.containsKey("smoke-dir") ? Path.of(options.get("smoke-dir")).toAbsolutePath() : scriptFolder();
        try {
            if (!Files.isDirectory(appImage) || version.isBlank()) {
                throw new Fail("usage: java SmokeTest.java --app <application image folder> --version <x.y.z> [--work <folder>]");
            }
            work = options.containsKey("work") ? Path.of(options.get("work")).toAbsolutePath()
                    : Files.createTempDirectory("atlas-smoke-");
            Files.createDirectories(work);
            listed = routeList(script.resolve("routes.txt"));
            Layout layout = Layout.of(appImage, direct);

            step("the app is built and its data files are plain");
            checkJar(layout);

            step("the app carries its licence, NOTICE and third-party notices");
            checkLicenceFiles(layout);

            Scenario scenario = new Scenario(layout, version, script);
            scenario.run();

            if (!os().equals("windows")) {
                new ReadOnlyRun(layout, version, script).run();
            }
            System.out.println();
            System.out.println("SMOKE TEST PASSED (" + called.size() + " routes called)");
        } catch (Fail e) {
            System.err.println();
            System.err.println("SMOKE TEST FAILED at step: " + step);
            System.err.println(e.getMessage());
            for (App app : apps) {
                app.dump();
            }
            exitCode = 1;
        } catch (Exception | Error e) {
            System.err.println();
            System.err.println("SMOKE TEST FAILED at step: " + step);
            e.printStackTrace();
            for (App app : apps) {
                app.dump();
            }
            exitCode = 1;
        } finally {
            for (App app : apps) {
                app.kill();
            }
            if (!keep && work != null) {
                makeWritable(work);
                deleteTree(work);
            } else if (work != null) {
                System.out.println("Throwaway folders kept in " + work);
            }
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** A window's connection: the stream, open, with its first comment line read. Closing it is closing the window. */
    static Socket holdStream(int port, String cookie) throws IOException {
        Socket stream = new Socket("127.0.0.1", port);
        stream.setSoTimeout(15_000);
        OutputStream out = stream.getOutputStream();
        out.write(("GET /api/session/stream HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAccept: text/event-stream\r\n"
                + "Cookie: " + cookie + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        expect(line != null && line.contains("200"), "the stream answered: " + line);
        StringBuilder seen = new StringBuilder();
        while ((line = in.readLine()) != null) {
            seen.append(line).append('\n');
            if (line.startsWith(": open")) {
                return stream;
            }
        }
        throw new Fail("the stream never said ': open' (saw: " + seen + ")");
    }

    static void step(String name) {
        step = name;
        System.out.println("-- " + name);
    }

    // ---- what is built

    /** Where things are inside the application image. */
    record Layout(Path app, Path launcher, Path runtimeJava, Path appJars, Path sqliteJar, Path appJar, boolean direct) {

        static Layout of(Path app, boolean direct) throws IOException {
            Path jars;
            Path launcher;
            Path runtime;
            switch (os()) {
                case "macos" -> {
                    jars = app.resolve("Contents/app");
                    runtime = app.resolve("Contents/runtime/Contents/Home/bin/java");
                    try (Stream<Path> files = Files.list(app.resolve("Contents/MacOS"))) {
                        launcher = files.filter(Files::isRegularFile).findFirst().orElseThrow(
                                () -> new Fail("no launcher in " + app.resolve("Contents/MacOS")));
                    }
                }
                case "windows" -> {
                    jars = app.resolve("app");
                    runtime = app.resolve("runtime/bin/java.exe");
                    try (Stream<Path> files = Files.list(app)) {
                        launcher = files.filter(p -> p.toString().endsWith(".exe")).findFirst().orElseThrow(
                                () -> new Fail("no .exe launcher in " + app));
                    }
                }
                default -> {
                    jars = app.resolve("lib/app");
                    runtime = app.resolve("lib/runtime/bin/java");
                    try (Stream<Path> files = Files.list(app.resolve("bin"))) {
                        launcher = files.filter(Files::isRegularFile).findFirst().orElseThrow(
                                () -> new Fail("no launcher in " + app.resolve("bin")));
                    }
                }
            }
            Path sqlite = find(jars, "sqlite-jdbc-");
            Path appJar = jars.resolve("butterfly-atlas-app.jar");
            if (!Files.isRegularFile(appJar)) {
                throw new Fail("the app's own jar is not at " + appJar);
            }
            if (!Files.isRegularFile(runtime)) {
                throw new Fail("the bundled Java runtime is not at " + runtime);
            }
            return new Layout(app, launcher, runtime, jars, sqlite, appJar, direct);
        }

        private static Path find(Path folder, String prefix) throws IOException {
            try (Stream<Path> files = Files.list(folder)) {
                return files.filter(p -> p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow(
                        () -> new Fail("no " + prefix + "*.jar in " + folder));
            }
        }
    }

    /**
     * The application image carries LICENSE (the Apache License 2.0), NOTICE and THIRD_PARTY_NOTICES.txt: in the app's
     * folder on Windows and Linux, in Contents/Resources on macOS. Looked for a few levels down, so either place counts.
     */
    static void checkLicenceFiles(Layout layout) throws IOException {
        Map<String, List<String>> needed = new LinkedHashMap<>();
        needed.put("LICENSE", List.of("Apache License", "Version 2.0, January 2004"));
        needed.put("NOTICE", List.of("Copyright 2026 We are Naturalists", "Apache License, Version 2.0"));
        needed.put("THIRD_PARTY_NOTICES.txt", List.of("Butterfly Atlas: third-party notices"));
        for (Map.Entry<String, List<String>> file : needed.entrySet()) {
            Path found;
            try (Stream<Path> files = Files.find(layout.app(), 3, (p, a) -> a.isRegularFile()
                    && p.getFileName().toString().equals(file.getKey()))) {
                found = files.findFirst().orElseThrow(() -> new Fail("the app has no " + file.getKey() + " in " + layout.app()));
            }
            String text = Files.readString(found);
            for (String words : file.getValue()) {
                if (!text.contains(words)) {
                    throw new Fail(found + " does not say: " + words);
                }
            }
        }
    }

    /** The app's jar holds its data as plain resources, and its entry point. */
    static void checkJar(Layout layout) throws IOException {
        try (ZipFile zip = new ZipFile(layout.appJar().toFile())) {
            Set<String> names = new TreeSet<>();
            zip.stream().map(ZipEntry::getName).forEach(names::add);
            for (String must : List.of("db/migration/V12__backup.sql", "db/migration/V1__core_schema.sql",
                    "places/places.sha256", "places/NOTICE.txt", "taxonomy/bundles.sha256", "heif/probe-rot.heic",
                    "heif/probe-mirror.heic", "db/migration/V14__heic_photos.sql", "db/migration/V15__TagNames.class")) {
                if (!names.contains(must)) {
                    throw new Fail("the app's jar has no " + must + " (data files must stay plain resources)");
                }
            }
            boolean plainJson = names.stream().anyMatch(n -> n.startsWith("taxonomy/taxonomy-india-") && n.endsWith(".json"));
            if (!plainJson) {
                throw new Fail("the app's jar has no taxonomy/taxonomy-india-*.json bundle");
            }
            if (!names.contains("com/butterflyatlas/app/Main.class")) {
                throw new Fail("the app's jar has no com/butterflyatlas/app/Main.class (the launcher's entry point)");
            }
        }
    }

    // ---- the main run

    /** The whole scenario against one running copy. */
    static final class Scenario {
        final Layout layout;
        final String version;
        final Path script;
        final Path config = work.resolve("c");
        final Path library = work.resolve("lib");
        final Path importFolder = work.resolve("in");
        final Path exportFolder = work.resolve("ex");
        final Path backupFolder = work.resolve("bk");
        final Map<String, Path> fixtures = new LinkedHashMap<>();
        App app;
        Http http;
        Socket stream;

        Scenario(Layout layout, String version, Path script) {
            this.layout = layout;
            this.version = version;
            this.script = script;
        }

        void run() throws Exception {
            Path fixtureFolder = script.resolve("fixtures");
            for (String name : List.of("smoke_jpeg.jpg", "smoke_png.png", "smoke_tiff.tif", "smoke_webp.webp")) {
                fixtures.put(name, fixtureFolder.resolve(name));
            }
            for (Path folder : List.of(config, library.getParent(), importFolder, exportFolder, backupFolder)) {
                Files.createDirectories(folder);
            }
            for (Path fixture : fixtures.values()) {
                Files.copy(fixture, importFolder.resolve(fixture.getFileName()));
            }

            step("the app starts");
            app = App.start(layout, config, null, "main");
            http = new Http(app.port());

            step("the secret is enforced: a request without the cookie gets 401");
            secret();

            step("the launch address gives one session cookie, once");
            launch();

            step("the window's connection is held open (the stream)");
            openStream();

            step("New window answers 204 (no browser here: its one-time address goes to the console)");
            http.call("POST /api/window", "/api/window", "{}", 204);

            step("the interface is served from the app");
            Resp page = http.get("/", true);
            expect(page.status() == 200 && page.text().toLowerCase(Locale.ROOT).contains("<html"),
                    "GET / gave " + page.status() + ", not the interface page");

            step("first run: the library folder is set up and the database migrates");
            setup();

            step("the Light or Dark choice is saved and answered");
            appearance();

            step("the taxonomy and place bundles loaded and passed their checksums");
            bundles();

            step("a JPEG, PNG, TIFF and WebP with GPS are added and thumbnailed");
            String species = photos();

            step("the other taxonomy, tag, place and photo routes answer");
            others(species);

            step("an export with Remove location yields files with no GPS");
            export();

            step("a database-only backup and an Everything backup complete, and the copied database opens");
            backup();

            step("the import routes answer");
            imports();

            step("deleting, restoring and emptying Recently deleted");
            deleted();

            step("a RAW photo is added, thumbnailed and shown from its embedded preview");
            raw();

            step("a HEIC photo is added" + ("macos".equals(os()) ? ", and thumbnailed and shown upright through sips" : "")
                    + "; its export needs the tick");
            heic();

            step("the status route answers");
            http.call("GET /api/status", "/api/status", null, 200);

            step("every route in routes.txt was called");
            coverage();

            step("the log has no launch key");
            logHasNoKeys();

            step("a second launch is refused with the notice (the running copy is not touched)");
            secondLaunch();

            step("closing the last connection quits the app within " + QUIT_BUDGET_SECONDS + " seconds");
            quits();
        }

        void secret() throws Exception {
            Resp api = http.get("/api/health", false);
            expect(api.status() == 401, "GET /api/health without the cookie gave " + api.status() + ", not 401");
            expect(api.text().contains("no_session"), "the 401 does not say no_session: " + api.text());
            Resp root = http.get("/", false);
            expect(root.status() == 401, "GET / without the cookie gave " + root.status() + ", not 401");
            Resp wrong = http.request("GET", "/api/health", null, Map.of("Cookie", "atlas_session_" + app.port() + "=wrong"));
            expect(wrong.status() == 401, "a wrong cookie gave " + wrong.status() + ", not 401");
        }

        void launch() throws Exception {
            Resp bad = http.request("GET", "/launch?k=not-a-key", null, Map.of());
            record(called, "GET /launch");
            expect(bad.status() == 401, "a wrong launch key gave " + bad.status() + ", not 401");
            Resp good = http.request("GET", "/launch?k=" + app.launchKey(), null, Map.of());
            // a page that moves on to / itself, never a redirect: a strict cookie set on a redirect in a chain that a file:
            // page began is not sent on to / (multi-window design §1)
            expect(good.status() == 200 && good.text().contains("location.replace(\"/\")"),
                    "the launch key gave " + good.status() + ", not 200 with the page that moves on to /: " + good.text());
            String cookie = good.header("set-cookie");
            expect(cookie != null && cookie.startsWith("atlas_session_" + app.port() + "="),
                    "no atlas_session_" + app.port() + " cookie was set: " + cookie);
            expect(cookie.contains("HttpOnly") && cookie.toLowerCase(Locale.ROOT).contains("samesite=strict"),
                    "the cookie is not HttpOnly and SameSite=Strict: " + cookie);
            http.setCookie(cookie.substring(0, cookie.indexOf(';')));
            Resp again = http.request("GET", "/launch?k=" + app.launchKey(), null, Map.of());
            expect(again.status() == 401, "a launch key worked twice (" + again.status() + ")");

            Path pointer = config.resolve("instance.json");
            expect(Files.isRegularFile(pointer), "no instance.json (the pointer file) in the config folder");
            Json pointerJson = Json.parse(Files.readString(pointer));
            String instanceKey = pointerJson.str("key");
            Resp refused = http.request("POST", "/launch/new", "{}", Map.of("X-Atlas-Key", "wrong"));
            record(called, "POST /launch/new");
            expect(refused.status() == 401, "POST /launch/new with a wrong key gave " + refused.status());
            Resp fresh = http.request("POST", "/launch/new", "{}", Map.of("X-Atlas-Key", instanceKey));
            expect(fresh.status() == 200 && Json.parse(fresh.text()).str("launch") != null,
                    "POST /launch/new with the pointer's key gave " + fresh.status() + ": " + fresh.text());
        }

        void openStream() throws Exception {
            stream = holdStream(app.port(), http.cookie());
            record(called, "GET /api/session/stream");
        }

        void appearance() throws Exception {
            Resp saved = http.call("PUT /api/appearance", "/api/appearance", "{\"theme\":\"dark\"}", 200);
            expect("dark".equals(Json.parse(saved.text()).str("theme")), "the appearance was not saved: " + saved.text());
            Resp read = http.call("GET /api/appearance", "/api/appearance", null, 200);
            expect("dark".equals(Json.parse(read.text()).str("theme")), "the saved appearance was not answered: " + read.text());
        }

        void setup() throws Exception {
            Json before = Json.parse(http.call("GET /api/setup", "/api/setup", null, 200).text());
            expect(!before.bool("configured"), "a new config folder says the library is already set up");
            Json inspect = Json.parse(http.call("GET /api/setup/inspect", "/api/setup/inspect?path=" + enc(library.toString()),
                    null, 200).text());
            expect(inspect.bool("absolute") && "create".equals(inspect.str("kind"))
                    && library.toString().equals(inspect.str("libraryFolder")), "setup/inspect does not accept " + library + ": " + inspect);
            Resp done = http.call("POST /api/setup", "/api/setup", "{\"dataFolder\":" + Json.quote(library.toString()) + "}", 200);
            expect(Json.parse(done.text()).bool("configured"), "setup did not configure the library: " + done.text());
            http.call("POST /api/setup/retry", "/api/setup/retry", "{}", null);
            Json session = Json.parse(http.call("GET /api/session", "/api/session", null, 200).text());
            expect(version.equals(session.str("version")), "the app says it is version " + session.str("version") + ", not " + version);
            expect("window".equals(session.str("mode")), "the session mode is " + session.str("mode"));
            Json health = Json.parse(http.call("GET /api/health", "/api/health", null, 200).text());
            expect("ok".equals(health.str("status")), "health: " + health);
            expect(Files.isRegularFile(library.resolve("atlas.db")), "no atlas.db in the library folder");
        }

        void bundles() throws Exception {
            Json summary = Json.parse(http.call("GET /api/summary", "/api/summary", null, 200).text());
            double total = summary.num("group.species.total");
            expect(total > 1000, "the taxonomy bundle has only " + total + " species");
            http.call("GET /api/tree", "/api/tree", null, 200);
            Json found = Json.parse(http.call("GET /api/search", "/api/search?q=" + enc("Arhopala centaurus"), null, 200).text());
            expect(found.list("results").size() > 0, "searching the taxonomy found nothing");
            Json places = Json.parse(http.call("GET /api/places/search", "/api/places/search?q=Pune", null, 200).text());
            expect(places.list("places").size() > 0, "searching the place list for Pune found nothing (place bundle)");
            http.call("GET /api/places/recent", "/api/places/recent", null, 200);
            expect(Files.isRegularFile(config.resolve("logs").resolve("atlas.log")), "no log file at " + config.resolve("logs/atlas.log"));
        }

        /** Adds the four photos; returns the species id. */
        String photos() throws Exception {
            Json found = Json.parse(http.call("GET /api/search", "/api/search?q=" + enc("Arhopala centaurus"), null, 200).text());
            String species = null;
            for (Object hit : found.list("results")) {
                Json j = (Json) hit;
                if ("species".equals(j.str("rank"))) {
                    species = j.str("id");
                    break;
                }
            }
            expect(species != null, "no species among the search hits");
            String[] places = {"Bengaluru Urban", "Pune", "Kolkata", "Chennai"};
            int i = 0;
            Map<Long, String> ids = new LinkedHashMap<>();
            for (Map.Entry<String, Path> fixture : fixtures.entrySet()) {
                String name = fixture.getKey();
                String type = name.endsWith(".png") ? "image/png" : name.endsWith(".tif") ? "image/tiff"
                        : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
                Resp added = http.upload(species, name, type, Files.readAllBytes(fixture.getValue()));
                expect(added.status() == 200, name + " was not added: " + added.status() + " " + added.text());
                Json photo = Json.parse(added.text());
                expect("added".equals(photo.str("status")), name + " was not added: " + added.text());
                long id = (long) photo.num("photo.id");
                ids.put(id, name);
                if (stateId == null) {
                    stateId = photo.str("photo.place.ids.1"); // the state of the first photo's district
                }
                String place = photo.str("photo.place.path.0");
                expect(places[i].equals(place), name + " (GPS) was placed in " + place + ", expected " + places[i]
                        + " (the place bundle or the metadata libraries failed)");
                i++;
            }
            long cover = ids.keySet().iterator().next();
            for (Map.Entry<Long, String> photo : ids.entrySet()) {
                long id = photo.getKey();
                byte[] thumb = waitForThumb(id);
                expect(thumb.length > 100, "the thumbnail of " + photo.getValue() + " is " + thumb.length + " bytes");
                Resp display = http.call("GET /api/photos/{id}/display", "/api/photos/" + id + "/display", null, 200);
                expect(display.body().length > 100, "the display image of " + photo.getValue() + " is empty");
                Resp original = http.call("GET /api/photos/{id}/original", "/api/photos/" + id + "/original", null, 200);
                expect(java.util.Arrays.equals(original.body(), Files.readAllBytes(fixtures.get(photo.getValue()))),
                        "the original of " + photo.getValue() + " is not the file that was added");
                expect(ExifGps.has(original.body()), "the original of " + photo.getValue()
                        + " has no GPS: the check itself is wrong (a control)");
            }
            expect(ids.size() == 4, "not four photos");
            photoIds = ids;
            speciesId = species;
            coverId = cover;
            Json list = Json.parse(http.call("GET /api/taxa/{id}/photos", "/api/taxa/" + species + "/photos", null, 200).text());
            expect(list.list("photos").size() == 4, "the species page lists " + list.list("photos").size() + " photos, not 4");
            return species;
        }

        Map<Long, String> photoIds;
        String stateId;
        String speciesId;
        long coverId;

        byte[] waitForThumb(long id) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (true) {
                Resp r = http.call("GET /api/photos/{id}/thumb", "/api/photos/" + id + "/thumb", null, null);
                boolean pending = "1".equals(r.header("x-atlas-pending"));
                String type = r.header("content-type");
                if (r.status() == 200 && !pending && type != null && type.startsWith("image/")) {
                    return r.body();
                }
                if (System.nanoTime() > deadline) {
                    throw new Fail("the thumbnail of photo " + id + " never came (last answer " + r.status() + ", pending=" + pending + ")");
                }
                Thread.sleep(250);
            }
        }

        void others(String species) throws Exception {
            // taxonomy
            http.call("GET /api/taxa/{id}", "/api/taxa/" + species, null, 200);
            Json summary = Json.parse(http.call("GET /api/summary", "/api/summary", null, 200).text());
            String family = summary.str("families.0.id");
            http.call("GET /api/taxa/{id}/children", "/api/taxa/" + family + "/children", null, 200);
            http.call("POST /api/taxa/{id}/search-words", "/api/taxa/" + species + "/search-words", "{\"word\":\"smokeword\"}", 200);
            http.call("DELETE /api/taxa/{id}/search-words", "/api/taxa/" + species + "/search-words?word=smokeword", null, 200);
            http.call("PUT /api/taxa/{id}/cover", "/api/taxa/" + species + "/cover", "{\"photoId\":" + coverId + "}", 200);

            // custom species
            http.call("GET /api/custom-species/check", "/api/custom-species/check?name=" + enc("Smokeus testus"), null, 200);
            Resp custom = http.call("POST /api/custom-species", "/api/custom-species",
                    "{\"familyId\":" + Json.quote(family) + ",\"name\":\"Smokeus testus\",\"common\":\"Smoke test butterfly\"}", null);
            expect(custom.status() == 200 || custom.status() == 201, "adding a custom species gave " + custom.status() + ": " + custom.text());
            String customId = Json.parse(custom.text()).str("id");
            expect(customId != null, "the custom species has no id: " + custom.text());
            http.call("PATCH /api/custom-species/{id}", "/api/custom-species/" + customId,
                    "{\"name\":\"Smokeus testus\",\"common\":\"Smoke test butterfly two\"}", 200);
            http.call("DELETE /api/custom-species/{id}", "/api/custom-species/" + customId, null, null);

            // taxonomy updates, splits, custom matches, place updates (nothing pending in a fresh library: answered, not failed)
            Json update = Json.parse(http.call("GET /api/update", "/api/update", null, 200).text());
            expect("none".equals(update.str("state")), "a new library says a taxonomy update is " + update.str("state"));
            http.call("POST /api/update/apply", "/api/update/apply", "{}", null);
            http.call("POST /api/update/retry", "/api/update/retry", "{}", null);
            http.call("POST /api/update/later", "/api/update/later", "{}", null);
            http.call("POST /api/update/dismiss", "/api/update/dismiss", "{}", null);
            http.call("GET /api/splits", "/api/splits", null, 200);
            http.call("GET /api/splits/{id}", "/api/splits/none", null, null);
            http.call("POST /api/splits/{id}/sort", "/api/splits/none/sort", "{\"photoIds\":[],\"target\":\"none\"}", null);
            http.call("GET /api/custom-matches", "/api/custom-matches", null, 200);
            http.call("POST /api/custom-matches/{customId}/merge", "/api/custom-matches/none/merge", "{\"officialId\":\"none\"}", null);
            http.call("POST /api/custom-matches/{customId}/decline", "/api/custom-matches/none/decline", "{\"officialId\":\"none\"}", null);
            http.call("GET /api/place-update", "/api/place-update", null, null);
            http.call("POST /api/place-update/dismiss", "/api/place-update/dismiss", "{}", null);

            // tags
            Resp tag = http.call("POST /api/tags", "/api/tags", "{\"label\":\"smoke_tag\"}", null);
            expect(tag.status() == 200 || tag.status() == 201, "creating a tag gave " + tag.status() + ": " + tag.text());
            Json tagJson = Json.parse(tag.text());
            long tagId = (long) tagJson.num("id");
            String tagKey = tagJson.str("key");
            http.call("GET /api/tags", "/api/tags", null, 200);
            Json names = Json.parse(http.call("GET /api/tag-names", "/api/tag-names", null, 200).text());
            names.list("merged");
            names.list("numbered");
            names.list("long"); // each throws when the list is missing
            expect(!names.isNull("dismissed"), "the tag names note has no dismissed: " + names);
            http.call("POST /api/tag-names/dismiss", "/api/tag-names/dismiss", "{}", 200);
            Json renamed = Json.parse(http.call("PATCH /api/tags/{id}", "/api/tags/" + tagId, "{\"label\":\"smoke tag two\"}", 200).text());
            expect("smoke_tag_two".equals(renamed.str("label")), "a renamed tag's name is not in its one form: " + renamed);
            long first = photoIds.keySet().iterator().next();
            http.call("PATCH /api/photos/{id}", "/api/photos/" + first, "{\"tags\":[" + Json.quote(tagKey) + "]}", 200);
            http.call("POST /api/photos/tags", "/api/photos/tags", "{\"ids\":[" + first + "],\"add\":[\"egg\"],\"remove\":[]}", 200);

            // places
            Resp place = http.call("POST /api/places", "/api/places", "{\"name\":\"Smoke_Test_Place\",\"parentId\":" + Json.quote(stateId) + "}", null);
            expect(place.status() == 200 || place.status() == 201, "adding a place gave " + place.status() + ": " + place.text());
            String placeId = Json.parse(place.text()).str("id");
            http.call("PATCH /api/places/{id}", "/api/places/" + placeId, "{\"name\":\"Smoke_Test_Place_Two\"}", 200);
            http.call("GET /api/places/mine", "/api/places/mine", null, 200);
            http.call("POST /api/photos/place", "/api/photos/place", "{\"ids\":[" + first + "],\"placeId\":" + Json.quote(placeId) + "}", 200);
            http.call("DELETE /api/places/{id}", "/api/places/" + placeId, null, 200);
            http.call("DELETE /api/tags/{id}", "/api/tags/" + tagId, null, 200);

            // moving a photo to another species and back
            Json other = Json.parse(http.call("GET /api/search", "/api/search?q=" + enc("Arhopala abseus"), null, 200).text());
            String otherId = null;
            for (Object hit : other.list("results")) {
                Json j = (Json) hit;
                if ("species".equals(j.str("rank")) && !species.equals(j.str("id"))) {
                    otherId = j.str("id");
                    break;
                }
            }
            expect(otherId != null, "no second species to move a photo to");
            http.call("POST /api/photos/move", "/api/photos/move", "{\"ids\":[" + first + "],\"taxonId\":" + Json.quote(otherId) + "}", 200);
            http.call("POST /api/photos/move", "/api/photos/move", "{\"ids\":[" + first + "],\"taxonId\":" + Json.quote(species) + "}", 200);

            // the shared filter
            String filter = "taxa=" + species;
            Json count = Json.parse(http.call("GET /api/filter/count", "/api/filter/count?" + filter, null, 200).text());
            expect((long) count.num("photos") == 4, "the filter counts " + count.num("photos") + " photos, not 4");
            http.call("GET /api/filter/species", "/api/filter/species?" + filter, null, 200);
            http.call("GET /api/filter/photos", "/api/filter/photos?" + filter, null, 200);
            http.call("GET /api/filter/places", "/api/filter/places?" + filter, null, 200);

            // the folder browser
            http.call("GET /api/fs/roots", "/api/fs/roots", null, 200);
            Json listing = Json.parse(http.call("GET /api/fs/list", "/api/fs/list?path=" + enc(importFolder.toString()), null, 200).text());
            expect((long) listing.num("photos") == 4, "the folder browser sees " + listing.num("photos") + " photos in the import folder, not 4");
        }

        void export() throws Exception {
            Json check = Json.parse(http.call("GET /api/exports/check", "/api/exports/check?what=both&path=" + enc(exportFolder.toString()),
                    null, 200).text());
            expect(check.isNull("problem"), "the export check refuses the folder: " + check);
            Resp started = http.call("POST /api/exports", "/api/exports", "{\"filter\":\"\",\"what\":\"both\",\"path\":"
                    + Json.quote(exportFolder.toString()) + ",\"layout\":\"flat\",\"stripLocation\":true,\"placeDepth\":\"district\"}", 201);
            long id = (long) Json.parse(started.text()).num("id");
            Json done = waitFor("GET /api/exports/{id}", "/api/exports/" + id, "done", 60);
            expect((long) done.num("copied") == 4, "the export copied " + done.num("copied") + " photos, not 4: " + done);
            expect((long) done.num("failed") == 0, "the export failed on " + done.num("failed") + " photos: " + done);
            Path folder = Path.of(done.str("path"));
            int seen = 0;
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".csv")) {
                        expect(Files.size(file) > 0, "the species CSV is empty");
                        continue;
                    }
                    seen++;
                    byte[] bytes = Files.readAllBytes(file);
                    expect(!ExifGps.has(bytes), "the exported " + name + " still carries GPS (location was not removed)");
                    expect(bytes.length > 100, "the exported " + name + " is empty");
                }
            }
            expect(seen == 4, "the export folder has " + seen + " photos, not 4");
            http.call("GET /api/exports", "/api/exports", null, 200);
            http.call("POST /api/exports/{id}/pause", "/api/exports/" + id + "/pause", "{}", null);
            http.call("POST /api/exports/{id}/resume", "/api/exports/" + id + "/resume", "{}", null);
            http.call("POST /api/exports/{id}/cancel", "/api/exports/" + id + "/cancel", "{\"remove\":false}", null);
            http.call("DELETE /api/exports/{id}", "/api/exports/" + id, null, null);
            http.call("DELETE /api/exports", "/api/exports", null, null);
        }

        void backup() throws Exception {
            http.call("GET /api/backup", "/api/backup", null, 200);
            http.call("PUT /api/backup/preference", "/api/backup/preference", "{\"preference\":\"less\"}", 200);
            for (String what : List.of("database", "everything")) {
                Json check = Json.parse(http.call("GET /api/backup/check", "/api/backup/check?what=" + what + "&path=" + enc(backupFolder.toString()),
                        null, 200).text());
                expect(check.isNull("problem"), "the backup check refuses the folder for " + what + ": " + check);
                Resp started = http.call("POST /api/backup", "/api/backup", "{\"what\":\"" + what + "\",\"path\":"
                        + Json.quote(backupFolder.toString()) + "}", 201);
                long id = (long) Json.parse(started.text()).num("id");
                Json done = waitFor("GET /api/backup/{id}", "/api/backup/" + id, "done", 60);
                expect((long) done.num("failed") == 0, "the " + what + " backup failed on " + done.num("failed") + " photos: " + done);
                Path folder = Path.of(done.str("path"));
                Path db = folder.resolve("atlas.db");
                expect(Files.isRegularFile(db), "the " + what + " backup has no atlas.db in " + folder);
                SqliteCheck.verify(layout.sqliteJar(), db);
                if (what.equals("everything")) {
                    long photos = 0;
                    try (Stream<Path> walk = Files.walk(folder.resolve("images"))) {
                        photos = walk.filter(Files::isRegularFile).count();
                    }
                    expect(photos == 4, "the Everything backup copied " + photos + " photo files, not 4");
                    http.call("POST /api/backup/{id}/pause", "/api/backup/" + id + "/pause", "{}", null);
                    http.call("POST /api/backup/{id}/resume", "/api/backup/" + id + "/resume", "{}", null);
                    http.call("POST /api/backup/{id}/cancel", "/api/backup/" + id + "/cancel", "{\"remove\":false}", null);
                    http.call("DELETE /api/backup/{id}", "/api/backup/" + id, null, null);
                }
            }
            http.call("DELETE /api/backup", "/api/backup", null, null);
        }

        void imports() throws Exception {
            Resp started = http.call("POST /api/imports", "/api/imports", "{\"path\":" + Json.quote(importFolder.toString()) + "}", null);
            expect(started.status() == 200 || started.status() == 201, "starting an import gave " + started.status() + ": " + started.text());
            long id = (long) Json.parse(started.text()).num("import.id");
            http.call("GET /api/imports", "/api/imports", null, 200);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            Json summary;
            while (true) {
                summary = Json.parse(http.call("GET /api/imports/{id}", "/api/imports/" + id, null, 200).text());
                if ("idle".equals(summary.str("phase"))) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new Fail("the import never finished reading the folder: " + summary);
                }
                Thread.sleep(250);
            }
            Json items = Json.parse(http.call("GET /api/imports/{id}/items", "/api/imports/" + id + "/items", null, 200).text());
            List<Object> list = items.list("items");
            expect(list.size() == 4, "the import found " + list.size() + " photos, not 4");
            List<Long> itemIds = new ArrayList<>();
            for (Object item : list) {
                Json j = (Json) item;
                expect("read".equals(j.str("state")), "an import item is " + j.str("state") + ": " + j);
                itemIds.add((long) j.num("id"));
            }
            long item = itemIds.get(0);
            http.call("GET /api/imports/{id}/items/{itemId}/preview", "/api/imports/" + id + "/items/" + item + "/preview", null, 200);
            http.call("GET /api/imports/{id}/items/{itemId}/display", "/api/imports/" + id + "/items/" + item + "/display", null, 200);
            String ids = itemIds.toString();
            http.call("POST /api/imports/{id}/accept-suggestions", "/api/imports/" + id + "/accept-suggestions", "{\"itemIds\":" + ids + "}", null);
            http.call("POST /api/imports/{id}/assign", "/api/imports/" + id + "/assign", "{\"itemIds\":" + ids + ",\"taxonId\":" + Json.quote(speciesId) + "}", null);
            http.call("POST /api/imports/{id}/tags", "/api/imports/" + id + "/tags", "{\"itemIds\":" + ids + ",\"add\":[\"egg\"],\"remove\":[]}", null);
            http.call("POST /api/imports/{id}/place", "/api/imports/" + id + "/place", "{\"itemIds\":" + ids + ",\"placeId\":null,\"remove\":false}", null);
            http.call("POST /api/imports/{id}/skip", "/api/imports/" + id + "/skip", "{\"itemIds\":" + ids + ",\"skipped\":true}", null);
            http.call("POST /api/imports/{id}/pause", "/api/imports/" + id + "/pause", "{}", null);
            http.call("PUT /api/imports/{id}/pairs", "/api/imports/" + id + "/pairs", "{\"choice\":\"both\"}", null);
            http.call("POST /api/imports/{id}/relocate", "/api/imports/" + id + "/relocate", "{\"path\":" + Json.quote(importFolder.toString()) + "}", null);
            http.call("POST /api/imports/{id}/file", "/api/imports/" + id + "/file", "{}", null);
            http.call("POST /api/imports/{id}/finish", "/api/imports/" + id + "/finish", "{\"skipRest\":true}", null);
            http.call("GET /api/imports/{id}/result", "/api/imports/" + id + "/result", null, null);
            http.call("DELETE /api/imports/{id}", "/api/imports/" + id, null, null);
        }

        /** Phase 7a: the built app finds a RAW file's JPEG preview, thumbnails it and shows it; then it goes again. */
        void raw() throws Exception {
            byte[] file = Files.readAllBytes(script.resolve("fixtures/raw.dng"));
            Resp added = http.upload(speciesId, "raw.dng", "image/x-raw", file);
            expect(added.status() == 200, "raw.dng was not added: " + added.status() + " " + added.text());
            Json photo = Json.parse(added.text());
            expect("added".equals(photo.str("status")), "raw.dng was not added: " + added.text());
            expect("raw".equals(photo.str("photo.fileType")), "raw.dng is not a RAW photo: " + added.text());
            expect((long) photo.num("photo.rawPreview.width") == 1024, "raw.dng's preview is not 1024 px wide: " + added.text());
            long id = (long) photo.num("photo.id");
            byte[] thumb = waitForThumb(id);
            expect(thumb.length > 100, "the thumbnail of raw.dng is " + thumb.length + " bytes");
            byte[] display = http.call("GET /api/photos/{id}/display", "/api/photos/" + id + "/display", null, 200).body();
            expect(display.length > 100 && (display[0] & 0xFF) == 0xFF && (display[1] & 0xFF) == 0xD8,
                    "the display image of raw.dng is not its JPEG preview");
            byte[] original = http.call("GET /api/photos/{id}/original", "/api/photos/" + id + "/original", null, 200).body();
            expect(java.util.Arrays.equals(original, file), "the original of raw.dng is not the file that was added");
            http.call("POST /api/photos/delete", "/api/photos/delete", "{\"ids\":[" + id + "]}", 200);
            http.call("POST /api/deleted/empty", "/api/deleted/empty", "{\"ids\":[" + id + "]}", 200);
        }

        /**
         * Phase 7b: the built app reads a HEIC's details in Java everywhere; on macOS its decoder is sips, which makes the
         * thumbnail and the display copy, turned upright (heic.heic is 1024 x 768 stored with irot 1: 768 x 1024 shown).
         * Its export needs the tick. Check again and the rescan note's dismissal answer; then the photo goes again.
         */
        void heic() throws Exception {
            byte[] file = Files.readAllBytes(script.resolve("fixtures/heic.heic"));
            Resp added = http.upload(speciesId, "IMG_0042.HEIC", "image/heic", file);
            expect(added.status() == 200, "heic.heic was not added: " + added.status() + " " + added.text());
            Json photo = Json.parse(added.text());
            expect("heic".equals(photo.str("photo.fileType")), "heic.heic is not a HEIC photo: " + added.text());
            expect((long) photo.num("photo.width") == 768 && (long) photo.num("photo.height") == 1024,
                    "heic.heic is not 768 x 1024 as displayed: " + added.text());
            expect(photo.str("photo.fileName").endsWith(".heic"), "heic.heic was not named .heic: " + added.text());
            long id = (long) photo.num("photo.id");
            Json checked = Json.parse(http.call("POST /api/heic/check", "/api/heic/check", "{}", 200).text());
            Json status = Json.parse(http.call("GET /api/status", "/api/status", null, 200).text());
            expect(checked.str("system") != null && status.str("heic.state") != null,
                    "the HEIC decoder's status is missing: " + checked + " / " + status);
            if ("macos".equals(os())) {
                expect("available".equals(checked.str("state")) && "sips".equals(checked.str("decoder")),
                        "Check again did not find sips: " + checked);
                expect("sips".equals(status.str("heic.decoder")), "the status does not say sips: " + status);
                int[] thumb = size(waitForThumb(id));
                expect(thumb[0] == 360 && thumb[1] == 480, "the HEIC thumbnail is " + thumb[0] + " x " + thumb[1]
                        + ", not 360 x 480 (upright)");
                Resp display = http.call("GET /api/photos/{id}/display", "/api/photos/" + id + "/display", null, 200);
                expect("image/jpeg".equals(display.header("content-type")), "the HEIC display copy is not a JPEG");
                int[] shown = size(display.body());
                expect(shown[0] == 768 && shown[1] == 1024, "the HEIC display copy is " + shown[0] + " x " + shown[1]
                        + ", not 768 x 1024 (upright)");
            }
            byte[] original = http.call("GET /api/photos/{id}/original", "/api/photos/" + id + "/original", null, 200).body();
            expect(java.util.Arrays.equals(original, file), "the original of heic.heic is not the file that was added");
            Json check = Json.parse(http.call("GET /api/exports/check", "/api/exports/check?what=photos&path="
                    + enc(exportFolder.toString()), null, 200).text());
            expect((long) check.num("heic.photos") == 1 && (long) check.num("heic.withGps") == 1,
                    "the export check does not count the HEIC photo with its GPS: " + check);
            expect("raw_location".equals(check.str("problem.code")), "the export does not ask for the tick: " + check);
            http.call("POST /api/rescan-left/dismiss", "/api/rescan-left/dismiss", "{}", 204);
            http.call("POST /api/photos/delete", "/api/photos/delete", "{\"ids\":[" + id + "]}", 200);
            http.call("POST /api/deleted/empty", "/api/deleted/empty", "{\"ids\":[" + id + "]}", 200);
        }

        /** A JPEG's width and height. */
        static int[] size(byte[] jpeg) throws IOException {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
            expect(image != null, "not a picture Java can read");
            return new int[] {image.getWidth(), image.getHeight()};
        }

        void deleted() throws Exception {
            long id = photoIds.keySet().stream().skip(3).findFirst().orElseThrow();
            Resp gone = http.call("POST /api/photos/delete", "/api/photos/delete", "{\"ids\":[" + id + "]}", 200);
            expect((long) Json.parse(gone.text()).num("deleted") == 1, "deleting a photo: " + gone.text());
            Json bin = Json.parse(http.call("GET /api/deleted", "/api/deleted", null, 200).text());
            expect(bin.list("photos").size() == 1, "Recently deleted lists " + bin.list("photos").size() + " photos, not 1");
            http.call("POST /api/deleted/restore", "/api/deleted/restore", "{\"ids\":[" + id + "]}", 200);
            http.call("POST /api/photos/delete", "/api/photos/delete", "{\"ids\":[" + id + "]}", 200);
            http.call("POST /api/deleted/empty", "/api/deleted/empty", "{\"ids\":[" + id + "]}", 200);
        }

        Json waitFor(String route, String path, String state, int seconds) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (true) {
                Json j = Json.parse(http.call(route, path, null, 200).text());
                if (state.equals(j.str("state"))) {
                    return j;
                }
                if (System.nanoTime() > deadline) {
                    throw new Fail(path + " never reached state " + state + " (last: " + j + ")");
                }
                Thread.sleep(250);
            }
        }

        void coverage() {
            List<String> missing = new ArrayList<>(listed);
            missing.removeAll(called);
            expect(missing.isEmpty(), "routes.txt lists routes this run never called: " + missing);
            List<String> extra = new ArrayList<>(called);
            extra.removeAll(listed);
            expect(extra.isEmpty(), "the smoke test called routes routes.txt does not list: " + extra);
        }

        void logHasNoKeys() throws Exception {
            String log = Files.readString(config.resolve("logs").resolve("atlas.log"));
            expect(!log.contains(app.launchKey()), "the log contains the launch key");
            String secret = http.cookie().substring(http.cookie().indexOf('=') + 1);
            expect(!log.contains(secret), "the log contains the session secret");
            expect(log.contains("is running on port"), "the log does not say the app started");
        }

        void secondLaunch() throws Exception {
            App second = App.startSecond(layout, config, "second");
            int code = second.waitExit(40);
            expect(code == 0, "the second launch exited with code " + code + " (expected 0, a window opened on the running copy)");
            expect(second.stdoutText().toLowerCase(Locale.ROOT).contains("already running"),
                    "the second launch did not say the app is already running");
            expect(!second.stdoutText().contains("is running at"), "the second launch started a server of its own");
            Resp health = http.call("GET /api/health", "/api/health", null, 200);
            expect(health.status() == 200, "the first copy stopped answering after the second launch");
        }

        void quits() throws Exception {
            stream.close();
            long start = System.nanoTime();
            int code = app.waitExit(QUIT_BUDGET_SECONDS);
            double took = (System.nanoTime() - start) / 1e9;
            expect(code == 0, "the app exited with code " + code + " after the last connection closed");
            System.out.printf("   quit %.1f s after the last connection closed%n", took);
            expect(!Files.exists(config.resolve("instance.json")), "the pointer file was not removed on quit");
        }
    }

    // ---- the app from a read-only copy of the app folder

    /** On macOS and Linux the app must write nothing inside its install folder: run it from a copy nobody can write to. */
    static final class ReadOnlyRun {
        final Layout layout;
        final String version;
        final Path script;

        ReadOnlyRun(Layout layout, String version, Path script) {
            this.layout = layout;
            this.version = version;
            this.script = script;
        }

        void run() throws Exception {
            step("read-only: the app runs from a read-only copy of its folder");
            Path copy = work.resolve("ro");
            Files.createDirectories(copy);
            Path target = copy.resolve(layout.app().getFileName());
            copyTree(layout.app(), target);
            makeReadOnly(target);
            Layout ro = Layout.of(target, layout.direct());
            Path config = work.resolve("roc");
            Path library = work.resolve("rolib");
            Files.createDirectories(config);
            App app = App.start(ro, config, null, "readonly");
            Http http = new Http(app.port());
            Resp good = http.request("GET", "/launch?k=" + app.launchKey(), null, Map.of());
            expect(good.status() == 200, "read-only: the launch key gave " + good.status());
            http.setCookie(good.header("set-cookie").split(";")[0]);
            Resp done = http.request("POST", "/api/setup", "{\"dataFolder\":" + Json.quote(library.toString()) + "}", Map.of());
            expect(done.status() == 200, "read-only: setup gave " + done.status() + " " + done.text());
            Path jpeg = script.resolve("fixtures/smoke_jpeg.jpg");
            Resp found = http.request("GET", "/api/search?q=" + enc("Arhopala centaurus"), null, Map.of());
            String species = null;
            for (Object hit : Json.parse(found.text()).list("results")) {
                if ("species".equals(((Json) hit).str("rank"))) {
                    species = ((Json) hit).str("id");
                    break;
                }
            }
            expect(species != null, "read-only: no species found");
            Resp added = http.upload(species, "smoke_jpeg.jpg", "image/jpeg", Files.readAllBytes(jpeg));
            expect(added.status() == 200, "read-only: a photo was not added: " + added.status() + " " + added.text());
            long id = (long) Json.parse(added.text()).num("photo.id");
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (true) {
                Resp thumb = http.request("GET", "/api/photos/" + id + "/thumb", null, Map.of());
                if (thumb.status() == 200 && !"1".equals(thumb.header("x-atlas-pending"))) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new Fail("read-only: the thumbnail never came");
                }
                Thread.sleep(250);
            }
            holdStream(app.port(), http.cookie()).close();
            int code = app.waitExit(QUIT_BUDGET_SECONDS);
            expect(code == 0, "read-only: the app exited with code " + code);
            System.out.println("   the app ran, added and thumbnailed a photo, and quit from a read-only folder");
        }
    }

    // ---- processes

    /** A running copy of the app: its output is collected on threads of its own. */
    static final class App {
        final Process process;
        final List<String> out = new CopyOnWriteArrayList<>();
        final List<String> err = new CopyOnWriteArrayList<>();
        final String label;
        final Path config;
        private volatile String address;

        private App(Process process, String label, Path config) {
            this.process = process;
            this.label = label;
            this.config = config;
            apps.add(this);
            pump(process.getInputStream(), out);
            pump(process.getErrorStream(), err);
        }

        static App start(Layout layout, Path config, Path dataDir, String label) throws Exception {
            App app = launch(layout, config, dataDir, label);
            long deadline = System.nanoTime() + Duration.ofSeconds(START_SECONDS).toNanos();
            while (app.address == null) {
                for (String line : app.out) {
                    if (line.startsWith("Open http://")) {
                        app.address = line.substring("Open ".length(), line.indexOf(" in a browser"));
                    }
                }
                if (app.address != null) {
                    break;
                }
                if (!app.process.isAlive()) {
                    throw new Fail("the app exited (code " + app.process.exitValue() + ") before it printed its launch address");
                }
                if (System.nanoTime() > deadline) {
                    throw new Fail("the app did not print its launch address within " + START_SECONDS + " seconds");
                }
                Thread.sleep(100);
            }
            return app;
        }

        static App startSecond(Layout layout, Path config, String label) throws Exception {
            return launch(layout, config, null, label);
        }

        private static App launch(Layout layout, Path config, Path dataDir, String label) throws IOException {
            List<String> properties = new ArrayList<>(List.of("-Datlas.noBrowser=true", "-Datlas.quitSeconds=1",
                    "-Datlas.configDir=" + config, "-Djava.awt.headless=true"));
            if (dataDir != null) {
                properties.add("-Datlas.dataDir=" + dataDir);
            }
            List<String> command = new ArrayList<>();
            ProcessBuilder builder;
            if (layout.direct()) {
                command.add(layout.runtimeJava().toString());
                command.addAll(properties);
                command.add("-cp");
                command.add(layout.appJars() + java.io.File.separator + "*");
                command.add("com.butterflyatlas.app.Main");
                builder = new ProcessBuilder(command);
            } else {
                command.add(layout.launcher().toString());
                builder = new ProcessBuilder(command);
                StringBuilder options = new StringBuilder();
                for (String property : properties) {
                    int eq = property.indexOf('=');
                    String value = property.substring(eq + 1);
                    options.append(options.length() == 0 ? "" : " ").append(property, 0, eq + 1)
                            .append(value.contains(" ") ? "\"" + value + "\"" : value);
                }
                builder.environment().put("JAVA_TOOL_OPTIONS", options.toString());
            }
            builder.environment().remove("ATLAS_PORT");
            builder.environment().remove("ATLAS_BROWSER");
            return new App(builder.start(), label, config);
        }

        int port() {
            return URI.create(address).getPort();
        }

        String launchKey() {
            String query = URI.create(address).getQuery();
            return query.substring(query.indexOf("k=") + 2);
        }

        String stdoutText() {
            return String.join("\n", out);
        }

        int waitExit(int seconds) throws InterruptedException {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new Fail("the app (" + label + ") was still running " + seconds + " seconds later");
            }
            Thread.sleep(200);
            return process.exitValue();
        }

        void kill() {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }

        void dump() {
            System.err.println("---- output of the app (" + label + "), last lines");
            List<String> all = new ArrayList<>(out);
            all.addAll(err);
            for (String line : all.subList(Math.max(0, all.size() - 40), all.size())) {
                System.err.println("   " + line);
            }
            Path log = config.resolve("logs").resolve("atlas.log");
            if (Files.isRegularFile(log)) {
                try {
                    List<String> lines = Files.readAllLines(log);
                    System.err.println("---- " + log + ", last lines");
                    for (String line : lines.subList(Math.max(0, lines.size() - 25), lines.size())) {
                        System.err.println("   " + line);
                    }
                } catch (IOException e) {
                    // nothing more to show
                }
            }
        }

        private static void pump(InputStream in, List<String> into) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        into.add(line);
                    }
                } catch (IOException e) {
                    // the process ended
                }
            }, "smoke-pump");
            thread.setDaemon(true);
            thread.start();
        }
    }

    // ---- HTTP

    record Resp(int status, byte[] body, java.net.http.HttpHeaders headers) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** The app's address with one session's cookie; every call to a registered route is recorded. */
    static final class Http {
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        private final int port;
        private String cookie;

        Http(int port) {
            this.port = port;
        }

        void setCookie(String cookie) {
            this.cookie = cookie;
        }

        String cookie() {
            return cookie;
        }

        Resp get(String path, boolean withCookie) throws IOException, InterruptedException {
            return request("GET", path, null, withCookie ? Map.of() : Map.of("X-No-Cookie", "1"));
        }

        /** A call to a route of routes.txt: recorded, never allowed to answer 5xx, and checked against {@code expected}. */
        Resp call(String route, String path, String body, Integer expected) throws IOException, InterruptedException {
            if (!listed.contains(route)) {
                throw new Fail("smoke test bug: " + route + " is not in routes.txt");
            }
            String method = route.substring(0, route.indexOf(' '));
            Resp r = request(method, path, body, Map.of());
            called.add(route);
            if (r.status() >= 500) {
                throw new Fail(route + " (" + path + ") answered " + r.status() + ": " + r.text());
            }
            if (expected != null && r.status() != expected) {
                throw new Fail(route + " (" + path + ") answered " + r.status() + ", expected " + expected + ": " + r.text());
            }
            return r;
        }

        Resp upload(String species, String name, String type, byte[] bytes) throws IOException, InterruptedException {
            String route = "POST /api/taxa/{id}/photos";
            HttpRequest request = builder("/api/taxa/" + species + "/photos?name=" + enc(name), true)
                    .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            called.add(route);
            if (response.statusCode() >= 500) {
                throw new Fail(route + " answered " + response.statusCode() + ": " + new String(response.body(), StandardCharsets.UTF_8));
            }
            return new Resp(response.statusCode(), response.body(), response.headers());
        }

        Resp request(String method, String path, String body, Map<String, String> headers) throws IOException, InterruptedException {
            boolean withCookie = !headers.containsKey("X-No-Cookie") && !headers.containsKey("Cookie");
            HttpRequest.Builder b = builder(path, withCookie);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (!h.getKey().equals("X-No-Cookie")) {
                    b.header(h.getKey(), h.getValue());
                }
            }
            boolean changes = !method.equals("GET");
            if (changes && body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else if (changes && !method.equals("DELETE")) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString("{}"));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Resp(response.statusCode(), response.body(), response.headers());
        }

        private HttpRequest.Builder builder(String path, boolean withCookie) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(60)).header("Origin", "http://127.0.0.1:" + port);
            if (withCookie && cookie != null) {
                b.header("Cookie", cookie);
            }
            return b;
        }
    }

    // ---- the copied database

    /** Opens a backed-up atlas.db with the SQLite driver the app ships, in this JVM. */
    static final class SqliteCheck {
        static void verify(Path sqliteJar, Path db) throws Exception {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {sqliteJar.toUri().toURL()}, SmokeTest.class.getClassLoader())) {
                Driver driver = (Driver) loader.loadClass("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
                Properties properties = new Properties();
                properties.setProperty("open_mode", "1"); // read-only
                try (Connection c = driver.connect("jdbc:sqlite:" + db.toString().replace('\\', '/'), properties);
                     Statement s = c.createStatement()) {
                    try (ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
                        expect(r.next() && "ok".equals(r.getString(1)), "the copied " + db + " fails integrity_check");
                    }
                    try (ResultSet r = s.executeQuery("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")) {
                        // The newest migration (V15, tag names): raise this with every new migration.
                        expect(r.next() && r.getInt(1) == 15, "the copied database is at schema version "
                                + (r.getInt(1)) + ", not 15");
                    }
                    try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM image")) {
                        expect(r.next() && r.getInt(1) == 4, "the copied database has " + r.getInt(1) + " photos, not 4");
                    }
                }
            }
        }
    }

    // ---- GPS in a photo file

    /** Whether a JPEG, PNG, TIFF or WebP carries a GPS directory (IFD0 tag 0x8825), read by hand, with no library. */
    static final class ExifGps {
        static boolean has(byte[] f) {
            byte[] tiff = exif(f);
            return tiff != null && ifd0Has(tiff, 0x8825);
        }

        private static byte[] exif(byte[] f) {
            if (f.length > 4 && (f[0] & 0xFF) == 0xFF && (f[1] & 0xFF) == 0xD8) {
                int p = 2;
                while (p + 4 < f.length && (f[p] & 0xFF) == 0xFF) {
                    int marker = f[p + 1] & 0xFF;
                    if (marker == 0xDA || marker == 0xD9) {
                        return null;
                    }
                    int length = ((f[p + 2] & 0xFF) << 8) | (f[p + 3] & 0xFF);
                    if (marker == 0xE1 && length >= 8 && new String(f, p + 4, 6, StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                        return java.util.Arrays.copyOfRange(f, p + 10, Math.min(f.length, p + 2 + length));
                    }
                    p += 2 + length;
                }
                return null;
            }
            if (f.length > 8 && (f[0] & 0xFF) == 0x89 && f[1] == 'P') {
                int p = 8;
                while (p + 8 <= f.length) {
                    int length = be32(f, p);
                    String type = new String(f, p + 4, 4, StandardCharsets.ISO_8859_1);
                    if (type.equals("eXIf")) {
                        return java.util.Arrays.copyOfRange(f, p + 8, p + 8 + length);
                    }
                    p += 12 + length;
                }
                return null;
            }
            if (f.length > 12 && f[0] == 'R' && f[1] == 'I' && f[8] == 'W') {
                int p = 12;
                while (p + 8 <= f.length) {
                    String type = new String(f, p, 4, StandardCharsets.ISO_8859_1);
                    int length = le32(f, p + 4);
                    if (type.equals("EXIF")) {
                        int start = p + 8;
                        if (length >= 6 && new String(f, start, 6, StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                            start += 6;
                        }
                        return java.util.Arrays.copyOfRange(f, start, p + 8 + length);
                    }
                    p += 8 + length + (length & 1);
                }
                return null;
            }
            if (f.length > 8 && ((f[0] == 'I' && f[1] == 'I') || (f[0] == 'M' && f[1] == 'M'))) {
                return f;
            }
            return null;
        }

        private static boolean ifd0Has(byte[] t, int tag) {
            boolean little = t[0] == 'I';
            int ifd = (int) u32(t, 4, little);
            if (ifd < 8 || ifd + 2 > t.length) {
                return false;
            }
            int count = (int) u16(t, ifd, little);
            for (int i = 0; i < count; i++) {
                int entry = ifd + 2 + i * 12;
                if (entry + 12 > t.length) {
                    return false;
                }
                if (u16(t, entry, little) == tag) {
                    return true;
                }
            }
            return false;
        }

        private static long u16(byte[] b, int p, boolean little) {
            return little ? ((b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)) : (((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF));
        }

        private static long u32(byte[] b, int p, boolean little) {
            long lo = u16(b, p, little);
            long hi = u16(b, p + 2, little);
            return little ? lo | (hi << 16) : (lo << 16) | hi;
        }

        private static int be32(byte[] b, int p) {
            return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
        }

        private static int le32(byte[] b, int p) {
            return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
        }
    }

    // ---- JSON (just enough to read the app's answers)

    /** A parsed JSON value: an object, array, string, number, boolean or null, read by dotted path ("photo.place.path.0"). */
    static final class Json {
        private final Object value;

        private Json(Object value) {
            this.value = value;
        }

        static Json parse(String text) {
            Parser p = new Parser(text);
            Object v = p.value();
            p.skip();
            if (p.i != text.length()) {
                throw new Fail("not JSON: " + text);
            }
            return new Json(v);
        }

        static String quote(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    default -> b.append(c);
                }
            }
            return b.append('"').toString();
        }

        @SuppressWarnings("unchecked")
        private Object at(String path) {
            Object v = value;
            for (String part : path.split("\\.")) {
                if (v instanceof Map<?, ?> m) {
                    v = ((Map<String, Object>) m).get(part);
                } else if (v instanceof List<?> l) {
                    int index = Integer.parseInt(part);
                    v = index < l.size() ? l.get(index) : null;
                } else {
                    return null;
                }
                if (v == null) {
                    return null;
                }
            }
            return v;
        }

        String str(String path) {
            Object v = at(path);
            return v instanceof String s ? s : null;
        }

        double num(String path) {
            Object v = at(path);
            if (v instanceof Double d) {
                return d;
            }
            throw new Fail("no number at '" + path + "' in " + this);
        }

        boolean bool(String path) {
            return Boolean.TRUE.equals(at(path));
        }

        boolean isNull(String path) {
            return at(path) == null;
        }

        List<Object> list(String path) {
            Object v = path.isEmpty() ? value : at(path);
            if (v instanceof List<?> l) {
                List<Object> wrapped = new ArrayList<>();
                for (Object o : l) {
                    wrapped.add(o instanceof Map || o instanceof List ? new Json(o) : o);
                }
                return wrapped;
            }
            throw new Fail("no list at '" + path + "' in " + this);
        }

        @Override
        public String toString() {
            String s = String.valueOf(value);
            return s.length() > 400 ? s.substring(0, 400) + "..." : s;
        }

        private static final class Parser {
            final String s;
            int i;

            Parser(String s) {
                this.s = s;
            }

            void skip() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            Object value() {
                skip();
                if (i >= s.length()) {
                    throw new Fail("not JSON: " + s);
                }
                char c = s.charAt(i);
                if (c == '{') {
                    i++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    skip();
                    if (s.charAt(i) == '}') {
                        i++;
                        return map;
                    }
                    while (true) {
                        skip();
                        String key = string();
                        skip();
                        i++; // :
                        map.put(key, value());
                        skip();
                        if (s.charAt(i++) == '}') {
                            return map;
                        }
                    }
                }
                if (c == '[') {
                    i++;
                    List<Object> list = new ArrayList<>();
                    skip();
                    if (s.charAt(i) == ']') {
                        i++;
                        return list;
                    }
                    while (true) {
                        list.add(value());
                        skip();
                        if (s.charAt(i++) == ']') {
                            return list;
                        }
                    }
                }
                if (c == '"') {
                    return string();
                }
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                if (start == i) {
                    throw new Fail("not JSON near: " + s.substring(Math.max(0, i - 20), Math.min(s.length(), i + 20)));
                }
                return Double.parseDouble(s.substring(start, i));
            }

            String string() {
                i++; // opening quote
                StringBuilder b = new StringBuilder();
                while (true) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return b.toString();
                    }
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n' -> b.append('\n');
                            case 't' -> b.append('\t');
                            case 'r' -> b.append('\r');
                            case 'b' -> b.append('\b');
                            case 'f' -> b.append('\f');
                            case 'u' -> {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> b.append(e);
                        }
                    } else {
                        b.append(c);
                    }
                }
            }
        }
    }

    // ---- small helpers

    static final class Fail extends RuntimeException {
        Fail(String message) {
            super(message);
        }
    }

    static void expect(boolean ok, String message) {
        if (!ok) {
            throw new Fail(message);
        }
    }

    static void record(Set<String> into, String route) {
        if (!listed.contains(route)) {
            throw new Fail("smoke test bug: " + route + " is not in routes.txt");
        }
        into.add(route);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return name.contains("win") ? "windows" : name.contains("mac") ? "macos" : "linux";
    }

    static Path scriptFolder() {
        String source = System.getProperty("smoke.dir");
        if (source != null) {
            return Path.of(source);
        }
        return Path.of(System.getProperty("user.dir")).resolve("packaging/smoke");
    }

    static List<String> routeList(Path file) throws IOException {
        List<String> routes = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            routes.add(t.contains("|") ? t.substring(0, t.indexOf('|')).strip() : t);
        }
        return routes;
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isSymbolicLink(source)) {
                    Files.createDirectories(target.getParent());
                    Files.createSymbolicLink(target, Files.readSymbolicLink(source));
                } else if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static void makeReadOnly(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(p);
                permissions.remove(PosixFilePermission.OWNER_WRITE);
                permissions.remove(PosixFilePermission.GROUP_WRITE);
                permissions.remove(PosixFilePermission.OTHERS_WRITE);
                Files.setPosixFilePermissions(p, permissions);
            }
        }
    }

    static void makeWritable(Path root) {
        if (os().equals("windows") || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(p);
                    permissions.add(PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(p, permissions);
                } catch (IOException e) {
                    // best effort
                }
            }
        } catch (IOException e) {
            // best effort
        }
    }

    static void deleteTree(Path folder) {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    // best effort: the system's temp folder is cleaned anyway
                }
            }
        } catch (IOException e) {
            // best effort
        }
    }

    private SmokeTest() {
    }
}
