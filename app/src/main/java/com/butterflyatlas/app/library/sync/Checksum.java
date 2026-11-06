package com.butterflyatlas.app.library.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-256 of a snapshot, as lower-case hex (one-device design §5): written into the marker beside the generation by the
 * device that publishes or claims, and compared by a device that claims, so a snapshot the sync app hasn't finished
 * downloading is never opened. It is the file's bytes only, so a rename keeps it.
 */
final class Checksum {

    private Checksum() {
    }

    static String of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
