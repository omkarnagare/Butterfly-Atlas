package com.butterflyatlas.app;

import com.butterflyatlas.app.config.AppDirs;
import com.butterflyatlas.app.db.LibraryTooNewException;
import com.butterflyatlas.app.library.LibraryChange;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.places.PlaceBundleException;
import com.butterflyatlas.app.taxonomy.BundleException;
import com.butterflyatlas.app.web.AtlasServices;
import com.butterflyatlas.app.web.Session;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.BrowserFinder;
import com.butterflyatlas.app.window.InstanceLock;
import com.butterflyatlas.app.window.InstancePointer;
import com.butterflyatlas.app.window.LaunchFiles;
import com.butterflyatlas.app.window.QuitTimer;
import com.butterflyatlas.app.window.Relaunch;
import com.butterflyatlas.app.window.SecondLaunch;
import com.butterflyatlas.app.window.SessionKeys;
import com.butterflyatlas.app.window.StartupFailure;
import com.butterflyatlas.app.window.WindowLauncher;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The start of the app, in order (design §2–§5, §15): a second launch is handled first (a window on the running copy, or
 * the message page); then the library and services open, the server starts on the fixed port or any free one with the
 * per-launch secret, the pointer file is written, the window opens, and the quit timer starts. A start that cannot go on
 * is shown, logged and exits with a code, never on stderr alone. The outside world (exit, the default browser, the
 * browser program, the second-launch check) is {@link World}, so a test runs the whole path in this process.
 */
final class AppStart {

    /**
     * The outside world of the start; {@link #system()} is the real one. {@code starter} starts the browser, and the
     * installed app again when a library change reopens it ({@code appPath}: the program the packaged launcher names in
     * {@code jpackage.app-path}, null in a development run).
     */
    record World(IntConsumer exit, Consumer<Thread> shutdownHook, Predicate<URI> defaultBrowser,
                 Supplier<BrowserFinder.Found> browser, WindowLauncher.Starter starter,
                 Function<InstancePointer, SecondLaunch> secondLaunch, Duration lockWait, int defaultPort,
                 Consumer<String> console, Supplier<String> appPath) {

        /** As the canonical one, the installed app's program read from the system property. */
        World(IntConsumer exit, Consumer<Thread> shutdownHook, Predicate<URI> defaultBrowser,
              Supplier<BrowserFinder.Found> browser, WindowLauncher.Starter starter,
              Function<InstancePointer, SecondLaunch> secondLaunch, Duration lockWait, int defaultPort,
              Consumer<String> console) {
            this(exit, shutdownHook, defaultBrowser, browser, starter, secondLaunch, lockWait, defaultPort, console,
                    () -> System.getProperty(Relaunch.APP_PATH));
        }

        static World system() {
            return new World(System::exit, Runtime.getRuntime()::addShutdownHook, AppStart::browse,
                    BrowserFinder.system()::find, command -> new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start(),
                    SecondLaunch::system, Duration.ofSeconds(30), Main.DEFAULT_PORT, System.out::println);
        }
    }

    /** The app, running: what a quit closes. */
    static final class Running implements AutoCloseable {

        private final AtlasServer server;
        private final AtlasServices services;
        private final QuitTimer timer;
        private final AtomicBoolean closing;
        private final InstancePointer pointer;
        private final String instanceKey;
        private final LaunchFiles launchFiles;
        private final Timer sweeper;
        private final Runnable releaseInstanceLock;
        private final Relaunch.Reopener reopener;

        private Running(AtlasServer server, AtlasServices services, QuitTimer timer, AtomicBoolean closing,
                        InstancePointer pointer, String instanceKey, LaunchFiles launchFiles, Timer sweeper,
                        Runnable releaseInstanceLock, Relaunch.Reopener reopener) {
            this.reopener = reopener;
            this.server = server;
            this.services = services;
            this.timer = timer;
            this.closing = closing;
            this.pointer = pointer;
            this.instanceKey = instanceKey;
            this.launchFiles = launchFiles;
            this.sweeper = sweeper;
            this.releaseInstanceLock = releaseInstanceLock;
        }

        AtlasServer server() {
            return server;
        }

        AtlasServices services() {
            return services;
        }

        /** What ends this copy of the app after a library change, reopening it when it can (contract §1). */
        Relaunch.Reopener reopener() {
            return reopener;
        }

        /** The moment a quit starts (audit 4): a second launch is told it is closing, and the pointer is gone. */
        void beginQuit() {
            closing.set(true);
            pointer.removeIfOurs(instanceKey);
            timer.close();
            sweeper.cancel();
            launchFiles.clear(); // no file is left holding a live launch key
            releaseInstanceLock.run(); // a launch from now on waits only for the library's lock, which the quit lets go of
        }

        /** A normal quit: {@link Main#shutdown} keeps its order. */
        @Override
        public void close() {
            beginQuit();
            Main.shutdown(server, services);
        }
    }

    /** A start that cannot go on: what to say, and the exit code. */
    private static final class Failed extends RuntimeException {

        private final transient StartupFailure.Message message;
        private final int code;

        Failed(StartupFailure.Message message, int code) {
            super(message.what());
            this.message = message;
            this.code = code;
        }
    }

    /** The config folder's lock file (owner-only folder). */
    static final String INSTANCE_LOCK = "instance.lock";

    private final AppOptions options;
    private final AtlasLog log;
    private final World world;
    /** Set when a second launch's window could not be opened (its start-up file could not be written). */
    private final AtomicBoolean secondFailed = new AtomicBoolean();
    /** How long a second launch waits for its browser to fail (tests shorten it). */
    Duration browserWait = Duration.ofSeconds(5);
    /** The config folder's lock, held from the first moment of the start until the quit starts (null: none held). */
    private InstanceLock instanceLock;

    AppStart(AppOptions options, AtlasLog log, World world) {
        this.options = options;
        this.log = log;
        this.world = world;
    }

    /** Starts the app; null when it did not (the exit was asked for, with its code). */
    Running run() {
        try {
            Running app = start();
            if (app == null) {
                releaseInstanceLock();
            }
            return app;
        } catch (Failed failed) {
            releaseInstanceLock();
            fail(failed.message, failed.code);
        } catch (RuntimeException | Error e) {
            releaseInstanceLock();
            log.error("Butterfly Atlas stopped on an unexpected error", e);
            fail(StartupFailure.unexpected(), StartupFailure.EXIT_FAILED);
        }
        return null;
    }

    private void releaseInstanceLock() {
        InstanceLock lock = instanceLock;
        instanceLock = null;
        if (lock != null) {
            try {
                lock.close();
            } catch (IOException e) {
                log.warn("Could not release the instance lock", e);
            }
        }
    }

    /** Shows, logs and exits for a start that could not even read its settings. */
    static void failBeforeStart(AtlasLog log, Path configDir, StartupFailure.Message message, World world) {
        StartupFailure.report(message, configDir, log, world.defaultBrowser(), world.console());
        world.exit().accept(StartupFailure.EXIT_FAILED);
    }

    private void fail(StartupFailure.Message message, int code) {
        StartupFailure.report(message, options.configDir(), log, world.defaultBrowser(), world.console());
        world.exit().accept(code);
    }

    private Running start() {
        InstancePointer pointer = new InstancePointer(options.configDir());
        // One copy at a time, from the first moment: a launch that comes while the first copy is still opening its library
        // finds no pointer yet, but finds this lock held, and waits for the pointer instead of starting a copy of its own.
        try {
            AppDirs.createConfigDir(options.configDir());
        } catch (RuntimeException e) {
            log.warn("Could not prepare " + options.configDir(), e);
        }
        InstanceLock.Result mine = InstanceLock.acquireFile(options.configDir().resolve(INSTANCE_LOCK));
        boolean starting = mine.state() == InstanceLock.State.HELD;
        instanceLock = mine.lock();
        SecondLaunch.Outcome other = world.secondLaunch().apply(pointer).run(this::openSecondWindow, starting);
        if (other == SecondLaunch.Outcome.OPENED) {
            log.info("Butterfly Atlas is already running; this launch opened a window on it.");
            world.exit().accept(secondFailed.get() ? StartupFailure.EXIT_FAILED : 0);
            return null;
        }
        if (other == SecondLaunch.Outcome.NOT_ANSWERING) {
            throw new Failed(StartupFailure.notAnswering(), StartupFailure.EXIT_NOT_ANSWERING);
        }

        // a copy that was quitting when this one was opened is waited for, once, while it lets go of the library
        AtomicBoolean first = new AtomicBoolean(true);
        Function<Path, InstanceLock.Result> locks = folder -> first.getAndSet(false)
                ? InstanceLock.acquireWithin(folder, world.lockWait()) : InstanceLock.acquire(folder);
        LaunchFiles launchFiles = new LaunchFiles(options.configDir(), Clock.systemUTC());
        launchFiles.clear(); // a file a crash left holds no live key: nothing of an earlier run survives
        AtlasServices services = openServices(locks);
        Path inUse = services.setup().libraryInUse();
        if (inUse != null) {
            // still held after the wait: another copy has the library; no server, no setup screen
            Main.closeServices(services);
            throw new Failed(StartupFailure.libraryInUse(inUse.toString()), StartupFailure.EXIT_NOT_ANSWERING);
        }

        SessionKeys keys = new SessionKeys(Clock.systemUTC());
        keys.onRedeemed(launchFiles::redeemed);
        log.hide(keys.secret());
        log.hide(keys.instanceKey());
        AtomicBoolean closing = new AtomicBoolean();
        AtomicReference<WindowLauncher> launcher = new AtomicReference<>();
        AtomicReference<Running> running = new AtomicReference<>();
        AtomicReference<AtlasServer> serving = new AtomicReference<>();
        QuitTimer timer = new QuitTimer(options.quitSeconds(), options.startSeconds(), !options.noBrowser(), System::nanoTime, System::currentTimeMillis,
                why -> {
                    WindowLauncher l = launcher.get();
                    if (l != null) {
                        l.fallBack(why);
                    }
                }, why -> quit(why, running, 0));
        Session session = new Session(keys, timer, () -> launcher.get() == null ? WindowLauncher.Mode.WINDOW
                : launcher.get().mode(), closing::get, options.configDir(), log.folder(), Session.PING,
                () -> newWindow(() -> serving.get().baseUri().resolve(SessionKeys.LAUNCH_QUERY + keys.newKey()), launchFiles,
                        timer::streams));
        AtlasServer server;
        try {
            server = startServer(services, session);
            serving.set(server);
        } catch (Failed failed) {
            Main.closeServices(services);
            throw failed;
        }
        try {
            pointer.write(InstancePointer.Info.ofThisProcess(server.port(), keys.instanceKey()));
        } catch (InstancePointer.AlreadyRunning e) {
            log.error("Another copy of Butterfly Atlas is running; this one does not replace its pointer", e);
            server.close();
            Main.closeServices(services);
            throw new Failed(StartupFailure.alreadyRunning(), StartupFailure.EXIT_NOT_ANSWERING);
        } catch (UncheckedIOException e) {
            log.error("Could not write the pointer file", e);
            server.close();
            Main.closeServices(services);
            throw new Failed(StartupFailure.folderNotWritable(options.configDir().toString()), StartupFailure.EXIT_FAILED);
        }
        Timer sweeper = new Timer("atlas-launch-files", true);
        sweeper.schedule(new TimerTask() {
            @Override
            public void run() {
                launchFiles.sweep(); // a key nobody used within its minute: its file goes (also another copy's leftovers)
            }
        }, 10_000, 10_000);
        Running app = new Running(server, services, timer, closing, pointer, keys.instanceKey(), launchFiles, sweeper,
                this::releaseInstanceLock, reopener(running));
        running.set(app);
        services.change().reopenWith(app.reopener(), LibraryChange.REOPEN_WAIT); // a library change ends this copy so
        world.shutdownHook().accept(new Thread(app::close, "atlas-shutdown"));
        log.info("Butterfly Atlas " + AtlasVersion.current() + " is running on port " + server.port());
        world.console().accept("Butterfly Atlas is running at " + server.baseUri());
        if (options.noBrowser()) {
            // for a developer's own browser: the one-time address (the console only, never the log)
            world.console().accept("Open " + server.baseUri().resolve(SessionKeys.LAUNCH_QUERY + keys.newKey())
                    + " in a browser to start a session (it works once, within a minute).");
        } else {
            WindowLauncher window = launcher(() -> server.baseUri().resolve(SessionKeys.LAUNCH_QUERY + keys.newKey()), launchFiles,
                    message -> {
                        StartupFailure.report(message, options.configDir(), log, world.defaultBrowser(), world.console());
                        quit(message.what(), running, StartupFailure.EXIT_FAILED);
                    });
            launcher.set(window);
            window.open();
        }
        timer.start();
        return app;
    }

    /** The last window closed (or none ever opened): the quit starts at once, the exit follows on a thread of its own. */
    private void quit(String why, AtomicReference<Running> running, int code) {
        log.info(why + " Butterfly Atlas is quitting.");
        Running app = running.get();
        Thread quitting = new Thread(() -> {
            if (app != null) {
                app.beginQuit();
            }
            world.exit().accept(code);
        }, "atlas-quit");
        quitting.start();
    }

    /**
     * Reopening after a library change (contract §1, spec §6): the quit begins (the pointer and the instance lock go, so
     * the new copy starts as a launch of its own and waits for nothing of this one but the library's lock), then the
     * installed app's program is started with no arguments and its output discarded; then the exit, on a thread of its
     * own. The 10-second quit timer is not used. A program that is missing or will not start is a quit that asks.
     */
    private Relaunch.Reopener reopener(AtomicReference<Running> running) {
        return new Relaunch.Reopener() {
            @Override
            public Relaunch.Plan plan() {
                return Relaunch.plan(world.appPath().get());
            }

            @Override
            public boolean begin(Relaunch.Plan plan) {
                Running app = running.get();
                if (app != null) {
                    app.beginQuit();
                }
                if (!plan.restarts()) {
                    log.info("The library folder changed; Butterfly Atlas quits and asks to be opened again ("
                            + plan.why() + ")");
                    return false;
                }
                try {
                    world.starter().start(plan.command());
                    log.info("The library folder changed; Butterfly Atlas started again to open it.");
                    return true;
                } catch (IOException | RuntimeException e) {
                    log.error("Could not start Butterfly Atlas again after the library folder changed", e);
                    return false;
                }
            }

            @Override
            public void exit() {
                log.info("The library folder changed. Butterfly Atlas is quitting.");
                new Thread(() -> world.exit().accept(0), "atlas-quit").start();
            }
        };
    }

    /** A window on the copy that is already running, with a fresh key from it; none when the settings say no browser. */
    private void openSecondWindow(Supplier<URI> address) {
        if (options.noBrowser()) {
            world.console().accept("Butterfly Atlas is already running.");
            return;
        }
        WindowLauncher window = launcher(address, new LaunchFiles(options.configDir(), Clock.systemUTC()), message -> {
            secondFailed.set(true);
            StartupFailure.report(message, options.configDir(), log, world.defaultBrowser(), world.console());
        });
        window.open();
        // this launch ends right after: a browser that fails within its first seconds is waited for, so the fallback runs
        window.awaitSettled(browserWait);
        if (window.noWindow() && !secondFailed.get()) {
            secondFailed.set(true);
            StartupFailure.report(StartupFailure.windowNotOpened(), options.configDir(), log, world.defaultBrowser(),
                    world.console());
        }
    }

    /**
     * New window ({@code POST /api/window}, multi-window contract §2, audit A12/A13): one more window exactly as a second
     * launch opens one, waited for while a failing browser fails. What stops it is logged, never shown as a start-up page
     * and never a quit: the window that asked says it. False when no window or tab could be opened. The answer comes as
     * soon as the browser has settled or the new window's page has connected (one more stream in {@code streams}), within
     * the time a failing browser takes to fail. Without a browser (the developer's setting) the one-time address goes to
     * the console, as at the start.
     */
    private boolean newWindow(Supplier<URI> address, LaunchFiles files, java.util.function.IntSupplier streams) {
        if (options.noBrowser()) {
            world.console().accept("Open " + address.get() + " in a browser to start a session (it works once, within a minute).");
            return true;
        }
        AtomicBoolean failed = new AtomicBoolean();
        WindowLauncher window = launcher(address, files, message -> {
            failed.set(true);
            log.warn("New window: " + message.what(), null);
        });
        int before = streams.getAsInt();
        window.open();
        window.awaitSettledOrShown(browserWait, () -> streams.getAsInt() > before);
        if (failed.get() || window.noWindow()) {
            log.warn("New window: no window or tab could be opened.", null);
            return false;
        }
        return true;
    }

    private WindowLauncher launcher(Supplier<URI> address, LaunchFiles files, Consumer<StartupFailure.Message> problem) {
        BrowserFinder.Found found = world.browser().get();
        if (found.note() != null) {
            log.warn(found.note(), null);
        }
        return new WindowLauncher(found.browser(), System.getProperty("os.name"),
                options.configDir().resolve("browser_profile"), address, files, world.starter(), world.defaultBrowser()::test,
                world.console(), log, System::nanoTime, problem);
    }

    /** Opens the library and the services; what stops that is told in plain words (never the raw error). */
    private AtlasServices openServices(Function<Path, InstanceLock.Result> locks) {
        try {
            return Main.services(options, locks);
        } catch (BundleException | PlaceBundleException e) {
            log.error("A bundled data file is damaged", e);
            throw new Failed(StartupFailure.damagedBundle(e.getMessage()), StartupFailure.EXIT_FAILED);
        } catch (LibraryTooNewException e) {
            log.error("The library is newer than this app", e);
            throw new Failed(StartupFailure.libraryTooNew(e.getMessage()), StartupFailure.EXIT_FAILED);
        } catch (RuntimeException e) {
            if (options.dataDir().isEmpty()) {
                throw e;
            }
            Path folder = options.dataDir().get().toAbsolutePath().normalize();
            log.error("Could not open the library at " + folder, e);
            if (e instanceof AtlasException atlas && ErrorCodes.LIBRARY_IN_USE.equals(atlas.code())) {
                throw new Failed(StartupFailure.libraryInUse(folder.toString()), StartupFailure.EXIT_FAILED);
            }
            throw new Failed(StartupFailure.libraryCannotOpen(folder.toString(), SetupService.reasonFor(folder, e)),
                    StartupFailure.EXIT_FAILED);
        }
    }

    /** The fixed port first, any free port when it is taken (D1); a port named in the settings is the only one tried. */
    private AtlasServer startServer(AtlasServices services, Session session) {
        Integer fixed = options.port();
        if (fixed != null) {
            try {
                return AtlasServer.start(fixed, services, session);
            } catch (RuntimeException e) {
                log.error("Could not start on port " + fixed, e);
                throw new Failed(fixed == 0 ? StartupFailure.unexpected() : StartupFailure.portTaken(fixed),
                        StartupFailure.EXIT_FAILED);
            }
        }
        try {
            return AtlasServer.start(world.defaultPort(), services, session);
        } catch (RuntimeException e) {
            log.info("Port " + world.defaultPort() + " is taken; using any free port instead.");
        }
        try {
            return AtlasServer.start(0, services, session);
        } catch (RuntimeException e) {
            log.error("Could not start the local service", e);
            throw new Failed(StartupFailure.unexpected(), StartupFailure.EXIT_FAILED);
        }
    }

    /** The default browser opens the address; false when this computer has none to open (or it failed). */
    static boolean browse(URI uri) {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return false;
        }
        try {
            Desktop.getDesktop().browse(uri);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
