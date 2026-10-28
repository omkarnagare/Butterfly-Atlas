package com.butterflyatlas.app.window;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Opens the app window (design §2): the browser found, started with {@code --app=<address>}, its own profile folder in
 * the config folder, {@code --no-first-run} and {@code --no-default-browser-check}. No browser, a browser that cannot be
 * started, or one that exits with an error within {@link #EARLY} (audit 2) means the default browser opens a tab instead
 * (once: {@link #fallBack}); {@link #mode()} then says {@code tab}. Every address has a fresh one-time launch key, which
 * is never in a program's arguments, the log or the console: the browser (and the default browser) is opened on an owner-only
 * redirect file ({@link LaunchFiles}) that sends it to the address. If that file cannot be written nothing is opened, and
 * the problem is told ({@code problem}); the key is never put in the command instead.
 */
public final class WindowLauncher {

    /** {@code WINDOW}: the app window; {@code TAB}: the default browser's tab (design §2). */
    public enum Mode {
        WINDOW, TAB
    }

    /** Starts the browser (the JDK's {@link ProcessBuilder}; tests give a fake). */
    @FunctionalInterface
    public interface Starter {
        Process start(List<String> command) throws IOException;
    }

    /** Opens the address in the default browser; true when it could be opened. */
    @FunctionalInterface
    public interface DefaultBrowser {
        boolean open(URI uri);
    }

    /** A browser that exits with an error sooner than this after starting did not show a window. */
    static final long EARLY_NANOS = 5_000_000_000L;

    private final Optional<Path> browser;
    private final Path profile;
    private final Supplier<URI> launchUri;
    private final LaunchFiles files;
    private final Starter starter;
    private final DefaultBrowser defaultBrowser;
    private final Consumer<String> console;
    private final AtlasLog log;
    private final LongSupplier nanos;
    private final Consumer<StartupFailure.Message> problem;
    private final AtomicBoolean fellBack = new AtomicBoolean();
    private volatile Mode mode = Mode.WINDOW;
    private volatile boolean noWindow;
    /** Completed when the browser has exited (and any fallback it caused is done), or at once when it was never started. */
    private final CompletableFuture<Void> settled = new CompletableFuture<>();

    /**
     * With the browser found, its profile folder, a supplier of a fresh launch address, the redirect files, how to start a
     * program, how to open the default browser, where the console's lines go, the log, a clock and where a problem that
     * stops any window from opening is told.
     */
    public WindowLauncher(Optional<Path> browser, Path profile, Supplier<URI> launchUri, LaunchFiles files, Starter starter,
                          DefaultBrowser defaultBrowser, Consumer<String> console, AtlasLog log, LongSupplier nanos,
                          Consumer<StartupFailure.Message> problem) {
        this.browser = browser;
        this.profile = profile;
        this.launchUri = launchUri;
        this.files = files;
        this.starter = starter;
        this.defaultBrowser = defaultBrowser;
        this.console = console;
        this.log = log;
        this.nanos = nanos;
        this.problem = problem;
    }

    /** The app window, or the fallback when there is no browser or it could not be started. */
    public void open() {
        if (browser.isEmpty()) {
            fallBack("No Edge or Chrome was found");
            settled.complete(null);
            return;
        }
        Path page = page();
        if (page == null) {
            settled.complete(null);
            return;
        }
        List<String> command = command(browser.get(), profile, page.toUri());
        long began = nanos.getAsLong();
        try {
            Files.createDirectories(profile);
            Process process = starter.start(command);
            process.onExit().thenAccept(p -> {
                try {
                    if (nanos.getAsLong() - began < EARLY_NANOS && p.exitValue() != 0) {
                        fallBack("The browser " + browser.get().getFileName() + " stopped with an error (code " + p.exitValue()
                                + ") right after starting");
                    }
                } finally {
                    settled.complete(null);
                }
            });
            log.info("Opened the app window with " + browser.get());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not start " + browser.get(), e);
            fallBack("The browser " + browser.get().getFileName() + " could not be started");
            settled.complete(null);
        }
    }

    /**
     * Waits up to {@code timeout} for the browser to have exited (a browser that fails does so within {@link #EARLY_NANOS}),
     * and for the fallback that follows. True when it had; false when the browser is still running (it is showing the window).
     * A launch that ends right after opening a window (the second launch) waits here, so a failure is not lost.
     */
    public boolean awaitSettled(Duration timeout) {
        try {
            settled.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** As {@link #awaitSettled(Duration)} for the time a failing browser takes to fail. */
    public boolean awaitEarlyExit() {
        return awaitSettled(Duration.ofNanos(EARLY_NANOS));
    }

    /**
     * As {@link #awaitSettled(Duration)}, but over as soon as {@code shown} says the window is up (its page has connected):
     * a browser that keeps running is then known to show it, with no need to wait out the time a failing one takes.
     * True when it settled or was shown.
     */
    public boolean awaitSettledOrShown(Duration timeout, java.util.function.BooleanSupplier shown) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (awaitSettled(Duration.ofNanos(Math.max(0, Math.min(left, 50_000_000L))))) {
                return true;
            }
            if (shown.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
        }
    }

    /** True when no window or tab could be opened at all (the problem is told, not shown on a window). */
    public boolean noWindow() {
        return noWindow;
    }

    /**
     * The default browser opens a tab with a fresh launch key, once however often it is asked ({@code why} goes to the
     * log). True when this call did it.
     */
    public boolean fallBack(String why) {
        if (!fellBack.compareAndSet(false, true)) {
            return false;
        }
        mode = Mode.TAB;
        Path page = page();
        if (page == null) {
            noWindow = true;
            return true;
        }
        if (defaultBrowser.open(page.toUri())) {
            log.info(why + "; opened Butterfly Atlas in the default browser instead.");
        } else {
            noWindow = true;
            log.warn(why + ", and the default browser could not be opened either.", null);
            console.accept("Butterfly Atlas is running. Open " + page + " in your browser (it works once, within a minute).");
        }
        return true;
    }

    /** The redirect file for a fresh launch address; null (and the problem told) when it cannot be written. */
    private Path page() {
        try {
            return files.write(launchUri.get());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write the start-up file in " + files.folder(), e);
            problem.accept(StartupFailure.launchFolderNotWritable(files.folder().getParent().toString()));
            return null;
        }
    }

    public Mode mode() {
        return mode;
    }

    /** {@code page} is the redirect file's address ({@code file:} URI), never the launch address with its key. */
    static List<String> command(Path browser, Path profile, URI page) {
        return List.of(browser.toString(), "--app=" + page, "--user-data-dir=" + profile, "--no-first-run",
                "--no-default-browser-check");
    }
}
