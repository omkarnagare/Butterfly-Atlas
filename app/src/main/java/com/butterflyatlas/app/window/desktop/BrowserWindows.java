package com.butterflyatlas.app.window.desktop;

import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.WindowLauncher;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Which browser process shows our windows (spec §3.2, contract §4). {@link WindowLauncher} tells it each browser it
 * starts ({@link #started}). The first one still running {@link WindowLauncher#EARLY_NANOS} after it started (so it did
 * not fail, and did not just hand its window to an older process and exit, as Chrome and Edge do with a second start on
 * the same profile) is <b>the browser process</b>; when it exits, the next one started that keeps running takes its place.
 *
 * <p>With none known (none started yet, every start handed off, or none has run long enough), the process that holds
 * the profile is asked for ({@link ProfileHolder}): the windows went to a browser this copy did not start, or one that is
 * still young. A process found that way is used only while it runs and when its program is the browser program (the
 * same file, or one in the folder a link to it leads to: {@link #sameProgram}); it is then kept until it exits. A lookup that fails is logged once and finds
 * nothing. Nothing here throws.
 */
public final class BrowserWindows {

    /** What is known of a running process. */
    @FunctionalInterface
    public interface Processes {

        /** The program the process runs; empty when it is not running, or its program cannot be told. */
        Optional<Path> program(long pid);

        /** This system's processes ({@link ProcessHandle}). */
        Processes SYSTEM = pid -> {
            try {
                return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(h -> h.info().command())
                        .map(BrowserWindows::path);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        };
    }

    private record Start(Process process, long startNanos) {
    }

    private final Supplier<Optional<Path>> browser;
    private final Path profile;
    private final ProfileHolder holder;
    private final Processes processes;
    private final LongSupplier nanos;
    private final OnceLog log;
    private final List<Start> starts = new ArrayList<>();
    private Start kept;
    private long found = -1;

    /**
     * With the browser program (as {@code BrowserFinder} finds it; empty when there is none), the profile folder, how the
     * process holding it is found, what is known of a running process, a clock in nanoseconds and the log.
     */
    public BrowserWindows(Supplier<Optional<Path>> browser, Path profile, ProfileHolder holder, Processes processes,
                          LongSupplier nanos, AtlasLog log) {
        this.browser = browser;
        this.profile = profile;
        this.holder = holder;
        this.processes = processes;
        this.nanos = nanos;
        this.log = new OnceLog(log);
    }

    /** A browser {@link WindowLauncher} started at {@code startNanos} (the clock given above). */
    public synchronized void started(Process process, long startNanos) {
        starts.add(new Start(process, startNanos));
    }

    /** The browser process's ID; empty when none is known. */
    public synchronized OptionalLong pid() {
        if (kept != null && kept.process().isAlive()) {
            return OptionalLong.of(kept.process().pid());
        }
        kept = null;
        if (found >= 0 && processes.program(found).isPresent()) {
            return OptionalLong.of(found);
        }
        found = -1;
        starts.removeIf(start -> !start.process().isAlive());
        long now = nanos.getAsLong();
        for (Start start : starts) {
            if (now - start.startNanos() >= WindowLauncher.EARLY_NANOS && hasId(start.process())) {
                kept = start;
                starts.remove(start);
                return OptionalLong.of(start.process().pid());
            }
        }
        return fromProfile();
    }

    /** The process holding the profile, when it runs the browser program. */
    private OptionalLong fromProfile() {
        Optional<Path> program;
        OptionalLong candidate;
        try {
            program = browser.get();
            if (program.isEmpty()) {
                return OptionalLong.empty();
            }
            candidate = holder.find(program.get(), profile);
        } catch (RuntimeException | LinkageError e) {
            log.warn("profile-lookup", "Butterfly Atlas could not find the browser that holds its profile " + profile
                    + "; the app's icon behaves as before.", e);
            return OptionalLong.empty();
        }
        if (candidate.isEmpty()) {
            return OptionalLong.empty();
        }
        long pid = candidate.getAsLong();
        Optional<Path> runs = processes.program(pid);
        if (runs.isEmpty()) {
            return OptionalLong.empty();
        }
        if (!sameProgram(program.get(), runs.get())) {
            log.info("profile-other-" + pid, "The process " + pid + " that holds the browser profile runs " + runs.get()
                    + ", not " + program.get() + "; it is not used.");
            return OptionalLong.empty();
        }
        found = pid;
        return OptionalLong.of(pid);
    }

    /**
     * The same file; or, when the browser program is a link (Linux: {@code /usr/bin/google-chrome} leads to the script
     * {@code /opt/google/chrome/google-chrome}, which starts {@code /opt/google/chrome/chrome}), a program in the folder
     * the link leads to. A program merely next to an ordinary one (two programs in {@code /usr/bin}) is not the same.
     */
    static boolean sameProgram(Path browser, Path process) {
        Path a = real(browser);
        Path b = real(process);
        if (a.equals(b)) {
            return true;
        }
        boolean link = !a.equals(browser.toAbsolutePath().normalize());
        return link && a.getParent() != null && Objects.equals(a.getParent(), b.getParent());
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static boolean hasId(Process process) {
        try {
            process.pid();
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static Path path(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            return Path.of("");
        }
    }
}
