package com.butterflyatlas.app.window;

import com.butterflyatlas.app.OwnerOnly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The redirect files a window is opened on. A launch key in the browser's arguments could be read by anyone else using the
 * computer, so the browser is started on {@code <config>/launch/open_<random>.html}, which sends it on to the launch
 * address. The folder is owner-only ({@code rwx------}; on Windows the user's own profile folder, and each file's access list
 * narrowed to its owner), each file is created new with {@code rw-------} from its first byte (a random name that is never
 * a link followed), and holds a live key only until the key is used ({@link #redeemed}) or has expired ({@link #sweep}, which
 * also removes a file another copy left by its age). The folder is emptied at the start and at the quit ({@link #clear}).
 */
public final class LaunchFiles {

    static final String FOLDER = "launch";
    static final String PREFIX = "open_";
    private static final Logger LOG = LoggerFactory.getLogger(LaunchFiles.class);
    private static final Pattern KEY = Pattern.compile("[?&]k=([A-Za-z0-9_-]+)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path folder;
    private final Clock clock;
    /** The files this copy wrote, by launch key, with when each was written (guarded by this). */
    private final Map<String, Written> written = new HashMap<>();

    private record Written(Path file, Instant at) {
    }

    public LaunchFiles(Path configDir, Clock clock) {
        this.folder = configDir.resolve(FOLDER);
        this.clock = clock;
    }

    public Path folder() {
        return folder;
    }

    /** Writes the redirect file for this launch address and returns it; an {@link IOException} when it cannot be. */
    public synchronized Path write(URI target) throws IOException {
        sweep();
        boolean posix = folder.getFileSystem().supportedFileAttributeViews().contains(OwnerOnly.POSIX);
        if (posix) {
            Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OwnerOnly.FOLDER)));
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(OwnerOnly.FOLDER));
        } else {
            Files.createDirectories(folder);
        }
        Path file = folder.resolve(PREFIX + HexFormat.of().formatHex(randomBytes()) + ".html");
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OwnerOnly.FILE))}
                : new FileAttribute<?>[0];
        try (SeekableByteChannel out = Files.newByteChannel(file, EnumSet.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE), attributes)) {
            if (!posix) {
                InstancePointer.narrowToOwner(file);
            }
            out.write(ByteBuffer.wrap(html(target).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        Matcher key = KEY.matcher(target.toString());
        if (key.find()) {
            written.put(key.group(1), new Written(file, clock.instant()));
        }
        return file;
    }

    /** A launch key was used: its file is deleted. */
    public synchronized void redeemed(String key) {
        Written file = written.remove(key);
        if (file != null) {
            delete(file.file());
        }
    }

    /** Deletes the files whose key has expired, and any other redirect file older than a key lives. */
    public synchronized void sweep() {
        Instant now = clock.instant();
        written.values().removeIf(w -> {
            if (!w.at().plus(SessionKeys.LIFE).isAfter(now)) {
                delete(w.file());
                return true;
            }
            return false;
        });
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> all = Files.newDirectoryStream(folder, PREFIX + "*.html")) {
            for (Path file : all) {
                boolean ours = written.values().stream().anyMatch(w -> w.file().equals(file));
                if (!ours && Files.getLastModifiedTime(file).toInstant().plus(SessionKeys.LIFE).isBefore(now)) {
                    delete(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not sweep {}", folder, e);
        }
    }

    /** Empties the folder (the start, the quit): no file left holds a live key. */
    public synchronized void clear() {
        written.clear();
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> all = Files.newDirectoryStream(folder)) {
            for (Path file : all) {
                delete(file);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not empty {}", folder, e);
        }
    }

    static String html(URI target) {
        String address = target.toString();
        return "<!doctype html><meta charset=utf-8><meta name=referrer content=no-referrer>"
                + "<title>Opening Butterfly Atlas</title><script>location.replace(\"" + script(address) + "\")</script>"
                + "<noscript><meta http-equiv=refresh content=\"0;url=" + attribute(address) + "\"></noscript>\n";
    }

    private static String script(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '/' -> out.append("\\/");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append('&');
                case '\n', '\r', ' ', ' ' -> out.append(String.format("\\u%04x", (int) c));
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String attribute(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
        }
    }
}
