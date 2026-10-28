package com.butterflyatlas.app.imaging.heif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One decode of a HEIC by the computer's own decoder program (Phase 7b design §4, contract §8). No user file name
 * reaches the program: the photo is hard-linked (or, where the drive cannot, copied) into the library's
 * {@code .thumbs/.decode/} folder under a random ASCII name, and the program writes another random name there. The
 * program is started by the absolute path its {@link Command} gives, with an argument list (no shell); its standard
 * input gets the command's fixed text or is closed, and its output is drained and kept up to {@value #OUTPUT_CAP} bytes
 * (logged, never shown). It has {@link #TIMEOUT} from its start; then every process it started and then itself are
 * killed. Its JPEG must exist, be at most {@value #MAX_JPEG} bytes, start as a JPEG, decode through ImageIO within
 * {@value #MAX_PIXELS} pixels, and have the size the HEIF boxes promise, turned or not, within 2 px after scaling.
 * Every file of the run (the decoder's extra ones too, such as heif-convert's depth images) is deleted when it ends,
 * whatever happens.
 *
 * <p>This class runs one program; how many run at once and in what order is {@link DecoderSlots}' business.
 */
public final class DecoderRun {

    /** How long the program may take, from its start (not from the wait for a slot). */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** The program's output kept for the log. */
    static final int OUTPUT_CAP = 64 * 1024;
    /** The largest JPEG accepted from a decoder. */
    static final long MAX_JPEG = 64L * 1024 * 1024;
    /** The largest picture accepted from a decoder (a 200-megapixel camera's HEIF fits). */
    static final long MAX_PIXELS = 250_000_000L;
    /** How far a decoded side may be from the promised one, after scaling. */
    static final int SIZE_SLACK = 2;

    private static final Logger LOG = LoggerFactory.getLogger(DecoderRun.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NAME = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** How the decoder program is started for one decode. */
    public interface Command {

        /** The full argument list (the program's absolute path first) for these two working files. */
        List<String> arguments(Path input, Path output, int longSide);

        /** Fixed text for the program's standard input (the Windows script), or null to close it at once. */
        default String input(Path input, Path output, int longSide) {
            return null;
        }

        /** Environment variables the program also gets (the Windows script's paths, fixes J3); none by default. */
        default Map<String, String> environment(Path input, Path output, int longSide) {
            return Map.of();
        }
    }

    /** How a run ended. */
    public enum Outcome {
        /** A checked picture. */
        DONE,
        /** The program ran and said it succeeded, but its JPEG failed a check: the file is not decodable here. */
        FAILED,
        /** The program failed (an exit code other than 0, or it could not be started): maybe the decoder broke. */
        PROGRAM_ERROR,
        /** The program could not be found at its path. */
        NO_PROGRAM,
        /** The program took longer than {@link #TIMEOUT}; it and what it started were killed. */
        TIMED_OUT,
        /** The working copy of the photo could not be made (the library's drive is full or gone): not the photo's fault. */
        NO_WORKSPACE
    }

    /**
     * What a run gave: the picture (decoded at between 1× and 2× the long side asked, or smaller), whether the decoder
     * turned it a quarter (its sides swapped against the stored size; false for a square picture), the program's output
     * (capped) and a few words on what went wrong.
     */
    public record Result(Outcome outcome, BufferedImage image, boolean swapped, String output, String why) {

        static Result of(Outcome outcome, String output, String why) {
            return new Result(outcome, null, false, output, why);
        }
    }

    private final Path folder;
    private final Command command;
    private final Duration timeout;

    /**
     * @param folder  the library's {@code .thumbs/.decode/} folder (created when needed)
     * @param command how the decoder is started
     * @param timeout {@link #TIMEOUT}, or shorter in tests
     */
    public DecoderRun(Path folder, Command command, Duration timeout) {
        this.folder = folder;
        this.command = command;
        this.timeout = timeout;
    }

    /**
     * Decodes {@code heic}, whose boxes promise a {@code storedWidth} × {@code storedHeight} picture, asking for at most
     * {@code longSide} pixels on its long side (never more than the picture's own). Never throws: every way it can end is a {@link Result}.
     */
    public Result decode(Path heic, int longSide, int storedWidth, int storedHeight) {
        longSide = Math.max(1, Math.min(longSide, Math.max(storedWidth, storedHeight))); // never asked to enlarge
        String base = randomName();
        Path input = folder.resolve(base + ".heic");
        Path output = folder.resolve(base + ".jpg");
        try {
            try {
                prepare(folder);
                link(heic, input);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                LOG.warn("Could not make the working copy of {} for the HEIC decoder: {}", heic, e.toString());
                return Result.of(Outcome.NO_WORKSPACE, "", "The working copy could not be made: " + e);
            }
            return run(input, output, longSide, storedWidth, storedHeight);
        } finally {
            deleteRun(base);
        }
    }

    private Result run(Path input, Path output, int longSide, int storedWidth, int storedHeight) {
        List<String> arguments = command.arguments(input, output, longSide);
        String text = command.input(input, output, longSide);
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(folder.toFile()).redirectErrorStream(true);
        builder.environment().putAll(command.environment(input, output, longSide));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            boolean missing = !Files.isRegularFile(Path.of(arguments.get(0)));
            LOG.info("The HEIC decoder {} could not be started: {}", arguments.get(0), e.toString());
            return Result.of(missing ? Outcome.NO_PROGRAM : Outcome.PROGRAM_ERROR, "", e.toString());
        }
        long started = System.nanoTime();
        Drain drain = new Drain(process.getInputStream());
        Thread drainer = Thread.ofVirtual().name("atlas-heic-output").start(drain);
        try (OutputStream stdin = process.getOutputStream()) {
            if (text != null) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // The program closed its input early (or ended): its exit code and output say what happened.
        }
        boolean ended;
        try {
            ended = process.waitFor(timeout.toNanos() - (System.nanoTime() - started), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            return Result.of(Outcome.TIMED_OUT, drain.text(), "Interrupted");
        }
        if (!ended) {
            kill(process);
            joinQuietly(drainer);
            LOG.info("The HEIC decoder took longer than {} s and was stopped", timeout.toSeconds());
            return Result.of(Outcome.TIMED_OUT, drain.text(), "Took longer than " + timeout.toSeconds() + " s");
        }
        joinQuietly(drainer);
        String said = drain.text();
        if (process.exitValue() != 0) {
            LOG.info("The HEIC decoder ended with {}: {}", process.exitValue(), said);
            return Result.of(Outcome.PROGRAM_ERROR, said, "Exit code " + process.exitValue());
        }
        return check(output, longSide, storedWidth, storedHeight, said);
    }

    /** The output checks (design §4 Limits); the picture when they pass. */
    private static Result check(Path output, int longSide, int storedWidth, int storedHeight, String said) {
        try {
            if (!Files.isRegularFile(output)) {
                return Result.of(Outcome.FAILED, said, "No JPEG was written");
            }
            long size = Files.size(output);
            if (size > MAX_JPEG) {
                return Result.of(Outcome.FAILED, said, "The JPEG is larger than " + MAX_JPEG + " bytes");
            }
            byte[] start = new byte[3];
            try (InputStream in = Files.newInputStream(output)) {
                if (in.readNBytes(start, 0, 3) < 3 || (start[0] & 0xFF) != 0xFF || (start[1] & 0xFF) != 0xD8
                        || (start[2] & 0xFF) != 0xFF) {
                    return Result.of(Outcome.FAILED, said, "Not a JPEG");
                }
            }
            try (ImageInputStream in = ImageIO.createImageInputStream(output.toFile())) {
                Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
                if (readers == null || !readers.hasNext()) {
                    return Result.of(Outcome.FAILED, said, "No reader for the JPEG");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    int w = reader.getWidth(0);
                    int h = reader.getHeight(0);
                    if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) {
                        return Result.of(Outcome.FAILED, said, "A picture of " + w + " x " + h);
                    }
                    Boolean swapped = matches(w, h, storedWidth, storedHeight);
                    if (swapped == null) {
                        return Result.of(Outcome.FAILED, said, "A picture of " + w + " x " + h + ", not of "
                                + storedWidth + " x " + storedHeight + " scaled");
                    }
                    ImageReadParam param = reader.getDefaultReadParam();
                    int step = Math.max(1, Math.max(w, h) / Math.max(1, longSide));
                    if (step > 1) {
                        param.setSourceSubsampling(step, step, 0, 0);
                    }
                    BufferedImage image = reader.read(0, param);
                    return new Result(Outcome.DONE, image, swapped, said, null);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            return Result.of(Outcome.FAILED, said, "The JPEG could not be read: " + e);
        }
    }

    /**
     * Whether a {@code w} × {@code h} picture is the stored one scaled (false), or turned a quarter and scaled (true);
     * null when it is neither, within {@value #SIZE_SLACK} px. A square picture is never "turned".
     */
    static Boolean matches(int w, int h, int storedWidth, int storedHeight) {
        if (fits(w, h, storedWidth, storedHeight)) {
            return false;
        }
        if (storedWidth != storedHeight && fits(w, h, storedHeight, storedWidth)) {
            return true;
        }
        return null;
    }

    private static boolean fits(int w, int h, int expectedW, int expectedH) {
        double scale = (double) Math.max(w, h) / Math.max(expectedW, expectedH);
        if (scale > 1.0 + 1e-9 && Math.max(w, h) > Math.max(expectedW, expectedH) + SIZE_SLACK) {
            return false; // never larger than the picture itself
        }
        return Math.abs(w - expectedW * scale) <= SIZE_SLACK && Math.abs(h - expectedH * scale) <= SIZE_SLACK;
    }

    /** Kills every process the program started, then the program; waits a little for them to go. */
    static void kill(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            process.descendants().forEach(descendants::add);
        } catch (RuntimeException e) {
            // listing may be refused; the program itself is still killed
        }
        for (ProcessHandle child : descendants) {
            child.destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
            for (ProcessHandle child : descendants) {
                child.onExit().get(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("A HEIC decoder's process did not end when stopped: {}", e.toString());
        }
    }

    /**
     * The photo under its working name: a hard link where the drive allows, else a copy. A photo marked read-only on
     * Windows is always copied (a link shares the attribute, and clearing it to delete the link would clear the photo's;
     * fixes J6), and the copy's mark is cleared.
     */
    private static void link(Path heic, Path input) throws IOException {
        if (readOnly(heic)) {
            Files.copy(heic, input);
            try {
                Files.setAttribute(input, "dos:readonly", false);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                // not a DOS file system
            }
            return;
        }
        try {
            Files.createLink(input, heic);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(heic, input);
        }
    }

    /** Whether the file carries Windows' read-only attribute (false where there is no such attribute). */
    static boolean readOnly(Path file) {
        try {
            return Boolean.TRUE.equals(Files.getAttribute(file, "dos:readonly"));
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return false;
        }
    }

    /** Deletes every file of the run: both working files and anything else the program wrote under the same name. */
    private void deleteRun(String base) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return; // never list through a link (fixes J4)
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, base + "*")) {
            for (Path file : files) {
                try {
                    deleteWorkingFile(file);
                } catch (IOException e) {
                    LOG.warn("Could not delete the HEIC decoder's working file {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            if (Files.isDirectory(folder)) {
                LOG.warn("Could not list the HEIC decoder's working folder {}: {}", folder, e.toString());
            }
        }
    }

    /**
     * Makes sure {@code folder} ({@code .thumbs/.decode}) is a real folder right under the library's real
     * {@code .thumbs} (review fix J4): a {@code .thumbs} that is a link is refused; a {@code .decode} that is a link (or
     * resolves anywhere else) is deleted as a link, never followed, and made again as a folder. An IOException when it
     * cannot be.
     */
    static void prepare(Path folder) throws IOException {
        Path thumbs = folder.getParent();
        if (Files.isSymbolicLink(thumbs)) {
            throw new IOException("The library's .thumbs is a link: " + thumbs);
        }
        Files.createDirectories(thumbs);
        if (Files.isSymbolicLink(folder)) {
            LOG.warn("The HEIC decoder's working folder {} was a link; it is made again as a folder", folder);
            Files.delete(folder); // the link itself, not what it points to
        }
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(folder);
        }
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                || !folder.toRealPath().equals(thumbs.toRealPath().resolve(folder.getFileName()))) {
            throw new IOException("The HEIC decoder's working folder is not a folder of its own: " + folder);
        }
    }

    /**
     * Empties the working folder (at every start, before any decode): leftovers of a run the app's end cut short. A
     * working folder that is a link is deleted as a link (what it points to is never touched) and made again (fixes J4).
     */
    public static void empty(Path folder) {
        if (folder.getParent() == null || !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            prepare(folder);
        } catch (IOException e) {
            LOG.warn("The HEIC decoder's working folder {} can't be used: {}", folder, e.toString());
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    deleteWorkingFile(file);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not empty the HEIC decoder's working folder {}: {}", folder, e.toString());
        }
    }

    /** Deletes a working file (a link as a link); on Windows its read-only attribute is cleared first (fixes J6). */
    static void deleteWorkingFile(Path file) throws IOException {
        try {
            if (!Files.isSymbolicLink(file) && Boolean.TRUE.equals(Files.getAttribute(file, "dos:readonly",
                    LinkOption.NOFOLLOW_LINKS))) {
                Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            // not a DOS file system (macOS, Linux): nothing to clear
        }
        Files.deleteIfExists(file);
    }

    /** 16 random lowercase letters and digits: nothing a program could read as an option, a path or shell syntax. */
    static String randomName() {
        char[] name = new char[16];
        for (int i = 0; i < name.length; i++) {
            name[i] = NAME[RANDOM.nextInt(NAME.length)];
        }
        return name[0] >= '0' && name[0] <= '9' ? "h" + new String(name, 1, 15) : new String(name);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads a program's output to its end, keeping the first {@value #OUTPUT_CAP} bytes. */
    private static final class Drain implements Runnable {

        private final InputStream in;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();

        Drain(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    synchronized (kept) {
                        int room = OUTPUT_CAP - kept.size();
                        if (room > 0) {
                            kept.write(buffer, 0, Math.min(room, n));
                        }
                    }
                }
            } catch (IOException e) {
                // the program was killed, or its output closed: what was kept is enough for the log
            }
        }

        String text() {
            synchronized (kept) {
                return kept.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
