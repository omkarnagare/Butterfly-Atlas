package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.library.Library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** File-system edges for photos: relative paths with {@code /}, moves that never overwrite, hashing. */
final class PhotoFiles {

    /** Uploads land here first, as {@code <uuid>.part}. */
    static final String INCOMING = Library.INCOMING;
    /** Photos in Recently deleted wait here. */
    static final String DELETED = ".deleted";

    private static final Logger LOG = LoggerFactory.getLogger(PhotoFiles.class);

    private PhotoFiles() {
    }

    /** {@code relPath} ({@code /}-separated, relative to the data folder) as a path under {@code data}. */
    static Path resolve(Path data, String relPath) {
        Path path = data;
        for (String part : relPath.split("/")) {
            if (!part.isEmpty()) {
                path = path.resolve(part);
            }
        }
        return path;
    }

    /** Moves a file to a path that must not exist yet: atomically where the file system can (same volume). */
    static void move(Path from, Path to) throws IOException {
        if (Files.exists(to)) {
            throw new FileAlreadyExistsException(to.toString());
        }
        Files.createDirectories(to.getParent());
        try {
            // POSIX rename may replace a target created in the tiny gap after the exists check (accepted;
            // the database lock covers the app's own files).
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(from, to);
            } catch (IOException failed) {
                Files.deleteIfExists(to);
                throw failed;
            }
        }
    }

    static boolean deleteQuietly(Path file) {
        if (file == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
            return false;
        }
    }

    /** Deletes the {@code .part} files in {@code <data>/.incoming} not touched for {@code minAge}; returns how many. */
    static int cleanIncoming(Path data, Duration minAge) {
        Path folder = data.resolve(INCOMING);
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(folder, "*.part")) {
            Instant cutoff = Instant.now().minus(minAge);
            for (Path part : parts) {
                if (Files.getLastModifiedTime(part).toInstant().isAfter(cutoff)) {
                    continue; // an upload still being written
                }
                if (deleteQuietly(part)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (removed > 0) {
            LOG.info("Removed {} unfinished uploads left from an earlier run", removed);
        }
        return removed;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
