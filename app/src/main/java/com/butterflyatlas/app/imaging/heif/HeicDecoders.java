package com.butterflyatlas.app.imaging.heif;

import com.butterflyatlas.app.photos.HeicDecodeException;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The computer's HEIC decoder (Phase 7b design §4–§5, contract §3 and §8, audit 3, 4, 8, 9, 11, 12): finding it at its
 * fixed path ({@code /usr/bin/sips} on a Mac, Windows PowerShell from the system folder, {@code heif-convert} in
 * {@code /usr/bin} or {@code /usr/local/bin} on Linux; never through {@code PATH}), checking it with the two built-in
 * probe pictures (which also say whether it turns and mirrors), holding the {@link HeicStatus} in memory (published to
 * {@link HeicStatus#current()}), and decoding photos through {@link DecoderRun} in {@link DecoderSlots}.
 *
 * <p>A check already running is joined, never doubled. The probes have 20 s in all; a check that timed out is tried
 * once more with 40 s, and only then is the decoder missing ({@code timed_out}). A decode that fails with a program
 * error (not an output check) checks the decoder again, at most once a minute: if the probes still pass, the photo is
 * at fault; if not, the decoder is missing and nothing is held against the photo. A program gone from its path marks
 * the decoder missing at once. The developer option {@code -Datlas.heicDecoder=none|sips|wic|heif-convert} forces
 * "missing" or one decoder's program.
 */
public final class HeicDecoders {

    /** The developer option naming the decoder to use ({@code none} for none). */
    public static final String PROPERTY = "atlas.heicDecoder";
    /** Time for both probes of one check (audit 11). */
    static final Duration PROBE_TIME = Duration.ofSeconds(20);
    /** Time for the second try of a check whose probes timed out (60 s in all, audit 4). */
    static final Duration PROBE_RETRY_TIME = Duration.ofSeconds(40);
    /** A decode's program error checks the decoder again at most this often. */
    static final Duration RECHECK_EVERY = Duration.ofMinutes(1);
    /** How long a use waits for a decoder slot (audit 9): the viewer 30 s, thumbnails 2 s (then they go back in line). */
    static final Duration VIEW_WAIT = Duration.ofSeconds(30);
    static final Duration THUMBNAIL_WAIT = Duration.ofSeconds(2);
    static final Duration IMPORT_WAIT = Duration.ofMinutes(10);

    private static final Logger LOG = LoggerFactory.getLogger(HeicDecoders.class);
    /** The reason when the library's working folder failed, not the decoder (fixes J3). */
    static final String WORKSPACE = "workspace";

    /** A decoder program found on this computer: its name in {@link HeicStatus#decoder()}, and how it is started. */
    public record Program(String name, Path path, DecoderRun.Command command, boolean wic) {
    }

    /** A decoded picture, and the EXIF orientation still to apply to show it upright (what the decoder did not). */
    public record Decoded(BufferedImage image, int orientation) {
    }

    /** What a check found: the status, and whether the decoder turns ({@code irot}) and mirrors ({@code imir}). */
    private record Found(HeicStatus status, Program program, boolean turns, boolean mirrors) {
    }

    private final String system;
    private final Supplier<Optional<Program>> finder;
    private final Supplier<Path> folder;
    private final Duration runTimeout;
    private final Duration probeTime;
    private final Duration probeRetryTime;
    private final LongSupplier nanos;
    private final DecoderSlots slots = new DecoderSlots();
    private final List<Consumer<HeicStatus>> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private volatile Found found;
    private CompletableFuture<HeicStatus> running;
    /** The last state a check settled on (null before the first). */
    private HeicStatus settled;
    private long lastRecheck = Long.MIN_VALUE;

    /**
     * For this computer: its system from {@code os.name}, its decoder at the fixed path (or as {@value #PROPERTY} says).
     *
     * @param folder the open library's {@code .thumbs/.decode/} folder
     */
    public HeicDecoders(Supplier<Path> folder) {
        this(HeicStatus.systemOf(System.getProperty("os.name", "")), folder, System.getProperty(PROPERTY));
    }

    HeicDecoders(String system, Supplier<Path> folder, String forced) {
        this(system, () -> find(system, forced), folder, DecoderRun.TIMEOUT, PROBE_TIME, PROBE_RETRY_TIME, System::nanoTime);
    }

    HeicDecoders(String system, Supplier<Optional<Program>> finder, Supplier<Path> folder, Duration runTimeout,
                 Duration probeTime, Duration probeRetryTime, LongSupplier nanos) {
        this.system = system;
        this.finder = finder;
        this.folder = folder;
        this.runTimeout = runTimeout;
        this.probeTime = probeTime;
        this.probeRetryTime = probeRetryTime;
        this.nanos = nanos;
        this.found = new Found(HeicStatus.checking(system), null, false, false);
    }

    /** The decoder program for a system (or the forced one), at its fixed path; empty when there is none. */
    static Optional<Program> find(String system, String forced) {
        String which = forced == null || forced.isBlank() ? switch (system) {
            case "mac" -> "sips";
            case "windows" -> "wic";
            case "linux" -> "heif-convert";
            default -> "none";
        } : forced.trim();
        Optional<Program> program = switch (which) {
            case "sips" -> Optional.of(sips(Path.of("/usr/bin/sips")));
            case "wic" -> Optional.of(wic(WicScript.powershell(System.getenv("SystemRoot"))));
            case "heif-convert" -> Optional.of(heifConvert(Files.isRegularFile(Path.of("/usr/bin/heif-convert"))
                    ? Path.of("/usr/bin/heif-convert") : Path.of("/usr/local/bin/heif-convert")));
            default -> Optional.empty();
        };
        return program.filter(p -> Files.isRegularFile(p.path()));
    }

    /** {@code sips -s format jpeg -s formatOptions 90 -Z <long side> in --out out}. */
    static Program sips(Path path) {
        return new Program("sips", path, (in, out, side) -> List.of(path.toString(), "-s", "format", "jpeg", "-s",
                "formatOptions", "90", "-Z", String.valueOf(side), in.toString(), "--out", out.toString()), false);
    }

    /** {@code heif-convert -q 90 in out} (full size: the app scales). */
    static Program heifConvert(Path path) {
        return new Program("heif-convert", path, (in, out, side) -> List.of(path.toString(), "-q", "90", in.toString(),
                out.toString()), false);
    }

    /** Windows PowerShell with {@link WicScript} on its standard input. */
    static Program wic(Path powershell) {
        return new Program("wic", powershell, new DecoderRun.Command() {
            @Override
            public List<String> arguments(Path input, Path output, int longSide) {
                return WicScript.arguments(powershell);
            }

            @Override
            public String input(Path input, Path output, int longSide) {
                return WicScript.SCRIPT;
            }

            @Override
            public Map<String, String> environment(Path input, Path output, int longSide) {
                return WicScript.environment(input, output, longSide);
            }
        }, true);
    }

    /** Called with the new status each time a check settles on another one than the last (from the checking thread). */
    public void onChange(Consumer<HeicStatus> listener) {
        listeners.add(listener);
    }

    public HeicStatus status() {
        return found.status();
    }

    /** Empties the working folder and checks the decoder in the background (at each start, when a library opens). */
    public void start() {
        Path work = folder.get();
        if (work != null) {
            DecoderRun.empty(work);
        }
        check();
    }

    /** Checks the decoder (joining a check already running); the future completes with the status it found. */
    public CompletableFuture<HeicStatus> check() {
        return check(false);
    }

    /**
     * A check; {@code quietly} (the re-check after a decode's program error, fixes J2) it does not show
     * {@code checking} meanwhile: thumbnails and the viewer keep going on the state as it was.
     */
    private CompletableFuture<HeicStatus> check(boolean quietly) {
        CompletableFuture<HeicStatus> mine = new CompletableFuture<>();
        synchronized (lock) {
            if (running != null && !running.isDone()) {
                return running;
            }
            running = mine;
            lastRecheck = nanos.getAsLong();
        }
        if (!quietly) {
            set(new Found(HeicStatus.checking(system), found.program(), found.turns(), found.mirrors()));
        }
        Thread.ofVirtual().name("atlas-heic-check").start(() -> {
            Found result;
            try {
                result = probeTwice();
            } catch (Throwable e) {
                LOG.warn("The HEIC decoder check failed", e);
                result = new Found(HeicStatus.missing(system, "failed"), null, false, false);
            }
            set(result);
            mine.complete(result.status());
        });
        return mine;
    }

    /** {@link #check()}, waiting at most {@code wait}; then the status as it is (still checking, contract §3, audit 11). */
    public HeicStatus checkAndWait(Duration wait) {
        CompletableFuture<HeicStatus> check = check();
        try {
            return check.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return status();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return status();
        } catch (ExecutionException e) {
            return status();
        }
    }

    /**
     * Sets the state. Listeners hear only a settled state (not {@code checking}) that differs from the last settled one
     * (fixes J2): a Check again that finds the same decoder again does not queue every HEIC photo once more.
     */
    private void set(Found next) {
        HeicStatus before;
        boolean tell;
        synchronized (lock) {
            before = found.status();
            found = next;
            HeicStatus.publish(next.status());
            tell = !next.status().isChecking() && !next.status().equals(settled);
            if (!next.status().isChecking()) {
                settled = next.status();
            }
        }
        if (!before.equals(next.status())) {
            LOG.info("HEIC decoder: {}", next.status());
        }
        if (tell) {
            for (Consumer<HeicStatus> listener : listeners) {
                try {
                    listener.accept(next.status());
                } catch (RuntimeException e) {
                    LOG.warn("A HEIC status listener failed", e);
                }
            }
        }
    }

    private boolean settledAvailable() {
        synchronized (lock) {
            return settled != null && settled.isAvailable();
        }
    }

    /** The probes, and once more with more time when they timed out. */
    private Found probeTwice() {
        Found first = probe(probeTime);
        if (!"timed_out".equals(first.status().reason())) {
            return first;
        }
        LOG.info("The HEIC decoder check timed out; trying once more");
        return probe(probeRetryTime);
    }

    /** One check: the program at its path, then both probes within {@code time}. */
    private Found probe(Duration time) {
        Optional<Program> program = finder.get();
        if (program.isEmpty()) {
            return new Found(HeicStatus.missing(system, "absent"), null, false, false);
        }
        Program p = program.get();
        long deadline = nanos.getAsLong() + time.toNanos();
        Probe rot = probeOne(p, "probe-rot.heic", deadline);
        Probe mirror = rot.missing() == null ? probeOne(p, "probe-mirror.heic", deadline) : rot;
        if (WORKSPACE.equals(rot.missing()) || WORKSPACE.equals(mirror.missing())) {
            // The library's working folder failed, not the decoder (fixes J3): a decoder that worked stays so.
            Found before = found;
            LOG.warn("The HEIC decoder could not be checked: no working copy could be made in the library");
            return before.program() != null && settledAvailable()
                    ? new Found(settled, before.program(), before.turns(), before.mirrors())
                    : new Found(HeicStatus.missing(system, WORKSPACE), p, false, false);
        }
        if (rot.missing() != null) {
            return new Found(HeicStatus.missing(system, rot.missing()), p, false, false);
        }
        if (mirror.missing() != null) {
            return new Found(HeicStatus.missing(system, mirror.missing()), p, false, false);
        }
        LOG.info("HEIC decoder {} works (turns: {}, mirrors: {})", p.name(), rot.applies(), mirror.applies());
        return new Found(HeicStatus.available(p.name(), system), p, rot.applies(), mirror.applies());
    }

    /** A probe's verdict: the reason the decoder is missing, or whether it applied the probe's transform. */
    private record Probe(String missing, boolean applies) {
    }

    /**
     * Decodes a probe (64 × 32, left red, right blue, a white top-left square) and finds the white square: where it is
     * says whether the decoder turned ({@code irot} 1: bottom left of a 32 × 64 picture) or mirrored ({@code imir} 1: top
     * right) the picture, or left it as stored (top left). Anywhere else, the decoder is not one the app can trust.
     */
    private Probe probeOne(Program program, String name, long deadline) {
        Path work = folder.get();
        if (work == null) {
            return new Probe("failed", false);
        }
        Path source = work.resolve(DecoderRun.randomName() + "-probe.heic");
        try {
            try (InputStream in = Objects.requireNonNull(HeicDecoders.class.getResourceAsStream("/heif/" + name), name)) {
                byte[] probe = in.readAllBytes();
                try {
                    Files.createDirectories(work);
                    Files.write(source, probe);
                } catch (IOException e) {
                    LOG.warn("No working copy of the probe {} could be made in {}: {}", name, work, e.toString());
                    return new Probe(WORKSPACE, false);
                }
            }
            long left = deadline - nanos.getAsLong();
            if (left <= 0) {
                return new Probe("timed_out", false);
            }
            if (!slots.acquire(PhotoProcessor.Use.VIEW, Duration.ofNanos(left))) {
                return new Probe("timed_out", false);
            }
            DecoderRun.Result result;
            try {
                left = Math.max(1, deadline - nanos.getAsLong());
                result = new DecoderRun(work, program.command(), Duration.ofNanos(left)).decode(source, 64, 64, 32);
            } finally {
                slots.release();
            }
            return switch (result.outcome()) {
                case DONE -> verdict(result, name.equals("probe-rot.heic"));
                case NO_PROGRAM -> new Probe("absent", false);
                case TIMED_OUT -> new Probe("timed_out", false);
                case NO_WORKSPACE -> new Probe(WORKSPACE, false);
                case PROGRAM_ERROR -> new Probe(program.wic() ? WicScript.reason(result.output()) : "failed", false);
                default -> {
                    LOG.info("The HEIC decoder failed its probe {}: {} {}", name, result.why(), result.output());
                    yield new Probe("failed", false);
                }
            };
        } catch (IOException | RuntimeException e) {
            LOG.warn("The HEIC decoder's probe {} could not be run", name, e);
            return new Probe("failed", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe("timed_out", false);
        } finally {
            try {
                Files.deleteIfExists(source);
            } catch (IOException e) {
                LOG.debug("Could not delete a probe's working copy", e);
            }
        }
    }

    private static Probe verdict(DecoderRun.Result result, boolean rot) {
        BufferedImage image = result.image();
        String corner = whiteCorner(image);
        if (rot) {
            if (!result.swapped() && "top-left".equals(corner)) {
                return new Probe(null, false);
            }
            if (result.swapped() && "bottom-left".equals(corner)) {
                return new Probe(null, true);
            }
        } else if (!result.swapped()) {
            if ("top-left".equals(corner)) {
                return new Probe(null, false);
            }
            if ("top-right".equals(corner)) {
                return new Probe(null, true);
            }
        }
        LOG.info("The HEIC decoder's probe came back {} with white at the {}: not a decoder the app can trust",
                result.swapped() ? "turned" : "as stored", corner);
        return new Probe("failed", false);
    }

    /** The corner whose pixel near the edge is white (all channels over 200); null when none or several. */
    static String whiteCorner(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int dx = Math.max(1, Math.min(w, h) / 16);
        String[] names = {"top-left", "top-right", "bottom-left", "bottom-right"};
        int[][] points = {{dx, dx}, {w - 1 - dx, dx}, {dx, h - 1 - dx}, {w - 1 - dx, h - 1 - dx}};
        String white = null;
        for (int i = 0; i < 4; i++) {
            int rgb = image.getRGB(points[i][0], points[i][1]);
            if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) > 200) {
                if (white != null) {
                    return null;
                }
                white = names[i];
            }
        }
        return white;
    }

    /**
     * Decodes a HEIC photo for {@code use}, at most {@code longSide} pixels on its long side, with its stored size and
     * turn from its boxes. Not available now (or a decoder that broke): {@link HeicDecodeException} UNAVAILABLE;
     * no slot in time: BUSY; too slow: TIMED_OUT; no working copy: NO_WORKSPACE. Any other IOException is the photo's
     * own failure (the decoder ran and could not read it).
     */
    public Decoded decode(Path heic, int longSide, HeifInfo info, PhotoProcessor.Use use) throws IOException {
        Found now = found;
        if (!now.status().isAvailable() || now.program() == null) {
            throw new HeicDecodeException(HeicDecodeException.Kind.UNAVAILABLE, "No HEIC decoder now: " + now.status());
        }
        if ((long) info.width() * info.height() > DecoderRun.MAX_PIXELS) {
            // Larger than the app ever reads: never handed to the decoder (fixes J8); the photo's own failure.
            throw new IOException("A picture of " + info.width() + " x " + info.height() + " is over the pixel limit");
        }
        Path work = folder.get();
        if (work == null) {
            throw new HeicDecodeException(HeicDecodeException.Kind.NO_WORKSPACE, "No library is open");
        }
        Duration wait = switch (use) {
            case VIEW -> VIEW_WAIT;
            case IMPORT -> IMPORT_WAIT;
            default -> THUMBNAIL_WAIT;
        };
        DecoderRun.Result result;
        try {
            if (!slots.acquire(use, wait)) {
                throw new HeicDecodeException(HeicDecodeException.Kind.BUSY, "No HEIC decoder slot came free");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeicDecodeException(HeicDecodeException.Kind.BUSY, "Interrupted while waiting for the decoder");
        }
        try {
            result = new DecoderRun(work, now.program().command(), runTimeout).decode(heic, longSide, info.width(),
                    info.height());
        } finally {
            slots.release();
        }
        return switch (result.outcome()) {
            case DONE -> decoded(result, info, now);
            case FAILED -> throw new IOException("The HEIC decoder's picture failed its check: " + result.why());
            case TIMED_OUT -> throw new HeicDecodeException(HeicDecodeException.Kind.TIMED_OUT, "timed out");
            case NO_WORKSPACE -> throw new HeicDecodeException(HeicDecodeException.Kind.NO_WORKSPACE, result.why());
            case NO_PROGRAM -> {
                set(new Found(HeicStatus.missing(system, "absent"), null, false, false));
                throw new HeicDecodeException(HeicDecodeException.Kind.UNAVAILABLE, "The HEIC decoder is gone");
            }
            case PROGRAM_ERROR -> {
                if (recheckAfterError().isAvailable()) {
                    throw new IOException("The HEIC decoder could not read this photo: " + result.why());
                }
                throw new HeicDecodeException(HeicDecodeException.Kind.UNAVAILABLE, "The HEIC decoder stopped working");
            }
        };
    }

    /** The picture and the orientation still to apply; a picture turned against what the check found fails. */
    private static Decoded decoded(DecoderRun.Result result, HeifInfo info, Found found) throws IOException {
        boolean turned = found.turns() && info.sideways();
        if (result.swapped() != turned && info.width().intValue() != info.height().intValue()) {
            throw new IOException("The HEIC decoder turned this photo " + (result.swapped() ? "" : "not ")
                    + "against what its check found");
        }
        return new Decoded(result.image(), info.orientation(!found.turns(), !found.mirrors()));
    }

    /** After a program error: a fresh check, unless one ran within the last minute; the status it leaves. */
    private HeicStatus recheckAfterError() {
        boolean recheck;
        synchronized (lock) {
            recheck = lastRecheck == Long.MIN_VALUE || nanos.getAsLong() - lastRecheck >= RECHECK_EVERY.toNanos()
                    || (running != null && !running.isDone());
        }
        if (!recheck) {
            return status();
        }
        try {
            return check(true).get(probeTime.plus(probeRetryTime).plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return status();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return status();
        }
    }
}
