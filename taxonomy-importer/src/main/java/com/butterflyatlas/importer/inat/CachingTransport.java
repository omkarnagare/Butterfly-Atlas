package com.butterflyatlas.importer.inat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Keeps every response on disk, keyed by a hash of its URL, so re-runs make no repeat requests. */
public final class CachingTransport implements Transport {

    private final Transport origin;
    private final Path directory;

    public CachingTransport(Transport origin, Path directory) {
        this.origin = origin;
        this.directory = directory;
    }

    @Override
    public String get(URI uri) throws IOException {
        Path file = directory.resolve(hash(uri.toString()) + ".json");
        if (Files.exists(file)) {
            return Files.readString(file);
        }
        String body = origin.get(uri);
        Files.createDirectories(directory);
        Path partial = Files.createTempFile(directory, "response", ".part");
        try {
            Files.writeString(partial, body);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
        return body;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
