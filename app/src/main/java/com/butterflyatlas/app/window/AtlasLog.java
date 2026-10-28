package com.butterflyatlas.app.window;

import com.butterflyatlas.app.OwnerOnly;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * The app's log file, {@code <config>/logs/atlas.log} (audit 1, 10): readable by its owner only, rolled into
 * {@code atlas.1.log}, {@code atlas.2.log}, … when it grows, and never holding a launch key (the {@code k=} of a launch
 * address, and any secret given to {@link #hide}). From an installed app's icon nobody sees the console, so what stopped
 * it, and every unexpected error's stack trace, is here. A log that cannot be written is not a reason to stop: the app
 * runs without it ({@link #file()} is then null).
 */
public final class AtlasLog {

    static final long MAX_BYTES = 1_000_000;
    static final int KEEP = 3;
    static final String NAME = "atlas";

    private static final Pattern KEY = Pattern.compile("([?&]k=|\\bk=)[A-Za-z0-9_-]+");

    private final Clock clock;
    private final Path folder;
    private final Path file;
    private final long maxBytes;
    private final int keep;
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private AtlasLog(Clock clock, Path folder, Path file, long maxBytes, int keep) {
        this.clock = clock;
        this.folder = folder;
        this.file = file;
        this.maxBytes = maxBytes;
        this.keep = keep;
    }

    public static AtlasLog open(Path configDir) {
        return open(configDir, Clock.systemUTC());
    }

    public static AtlasLog open(Path configDir, Clock clock) {
        return open(configDir, clock, MAX_BYTES, KEEP);
    }

    static AtlasLog open(Path configDir, Clock clock, long maxBytes, int keep) {
        Path folder = configDir.resolve("logs");
        try {
            Files.createDirectories(folder);
            ownerOnly(folder, OwnerOnly.FOLDER);
            Path file = folder.resolve(NAME + ".log");
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
            ownerOnly(file, OwnerOnly.FILE);
            return new AtlasLog(clock, folder, file, maxBytes, keep);
        } catch (IOException | RuntimeException e) {
            System.out.println("Butterfly Atlas could not open its log file in " + folder + ": " + e);
            return new AtlasLog(clock, folder, null, maxBytes, keep);
        }
    }

    /** The log file, or null when it could not be opened. */
    public Path file() {
        return file;
    }

    /** The folder the log is in (shown in Settings, About). */
    public Path folder() {
        return folder;
    }

    /** From now on this text never reaches the log (a launch key, the session's secret). */
    public void hide(String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
    }

    public void info(String message) {
        write("INFO", message, null);
    }

    public void warn(String message, Throwable cause) {
        write("WARN", message, cause);
    }

    public void error(String message, Throwable cause) {
        write("ERROR", message, cause);
    }

    /** From now on what the app and its libraries print to standard error also goes to the log (and still to the console). */
    public void captureStdErr() {
        PrintStream console = System.err;
        OutputStream tee = new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                console.write(b);
                if (b == '\n') {
                    flushLine();
                } else {
                    line.write(b);
                }
            }

            @Override
            public synchronized void flush() {
                console.flush();
            }

            private void flushLine() {
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (!text.isBlank()) {
                    append(scrub(text.stripTrailing()));
                }
            }
        };
        System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
    }

    private void write(String level, String message, Throwable cause) {
        StringBuilder text = new StringBuilder(clock.instant().toString()).append(' ').append(level).append(' ')
                .append(message);
        if (cause != null) {
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            text.append('\n').append(trace.toString().stripTrailing());
        }
        append(scrub(text.toString()));
    }

    private String scrub(String text) {
        String clean = KEY.matcher(text).replaceAll("$1...");
        for (String secret : secrets) {
            clean = clean.replace(secret, "...");
        }
        return clean;
    }

    private void append(String text) {
        if (file == null) {
            return;
        }
        synchronized (lock) {
            try {
                if (Files.size(file) >= maxBytes) {
                    roll();
                }
                Files.writeString(file, text + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                // nowhere left to say it: the app carries on
            }
        }
    }

    /** {@code atlas.log} becomes {@code atlas.1.log}, which becomes {@code atlas.2.log}, …; the oldest goes. */
    private void roll() throws IOException {
        Files.deleteIfExists(numbered(keep));
        for (int n = keep - 1; n >= 1; n--) {
            if (Files.exists(numbered(n))) {
                Files.move(numbered(n), numbered(n + 1));
            }
        }
        Files.move(file, numbered(1));
        Files.createFile(file);
        ownerOnly(file, OwnerOnly.FILE);
    }

    private Path numbered(int n) {
        return folder.resolve(NAME + "." + n + ".log");
    }

    /** Owner-only on systems with POSIX permissions; Windows' own ACL of the profile folder already is the user's. */
    private static void ownerOnly(Path path, String permissions) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains(OwnerOnly.POSIX)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }
}
