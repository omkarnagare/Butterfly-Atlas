package com.butterflyatlas.importer.bundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes {@code bundles.sha256} next to the bundles: one {@code <sha256>  <file name>} line per
 * bundle, sorted by name ({@code sha256sum} format). The app reads it as its list of shipped
 * bundles and refuses any bundle whose bytes no longer match.
 */
public final class BundleChecksums {

    public static final String FILE_NAME = "bundles.sha256";

    private static final Pattern BUNDLE = Pattern.compile("^taxonomy-[a-z0-9-]+-\\d{4}-\\d{2}-\\d{2}\\.json$");

    private BundleChecksums() {
    }

    public static Path write(Path directory) throws IOException {
        List<Path> bundles;
        try (Stream<Path> files = Files.list(directory)) {
            bundles = files
                    .filter(file -> BUNDLE.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        }
        StringBuilder content = new StringBuilder();
        for (Path bundle : bundles) {
            content.append(sha256(Files.readAllBytes(bundle))).append("  ").append(bundle.getFileName()).append('\n');
        }
        Path target = directory.resolve(FILE_NAME);
        Path temp = directory.resolve(FILE_NAME + ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
