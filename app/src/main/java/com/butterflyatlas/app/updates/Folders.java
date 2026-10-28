package com.butterflyatlas.app.updates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The folders a step's photos left (5b contract §3 stage 3): removed when empty, deepest first, never {@code images/}
 * itself nor a folder a live photo is in; a folder kept because it holds other files (a {@code notes.txt} of the user's)
 * is listed in the result. The clutter a file browser leaves ({@code .DS_Store}, {@code ._*}, {@code Thumbs.db},
 * {@code desktop.ini}) is removed with a folder that holds nothing else, and never keeps one; no other file is removed.
 */
final class Folders {

    private static final Logger LOG = LoggerFactory.getLogger(Folders.class);
    private static final Set<String> CLUTTER = Set.of(".ds_store", "thumbs.db", "desktop.ini");

    /** Runs before a folder is read (tests make a read fail). */
    static volatile Consumer<Path> reading = folder -> { };

    private Folders() {
    }

    /**
     * The folders of a file's path below {@code images/}, deepest first: {@code images/A/B/x.jpg} →
     * {@code images/A/B/}, {@code images/A/}.
     */
    static List<String> parents(String relPath) {
        List<String> parents = new ArrayList<>();
        int end = relPath.lastIndexOf('/');
        while (end > 0) {
            String folder = relPath.substring(0, end + 1);
            if (folder.equals("images/")) {
                break;
            }
            parents.add(folder);
            end = relPath.lastIndexOf('/', end - 1);
        }
        return parents;
    }

    /** The folders kept because they hold other files, and those emptied whose removal failed, each in name order. */
    record Removed(List<String> kept, List<String> notRemoved) {
    }

    /**
     * Removes the empty ones of {@code folders} (relative to {@code data}, ending in {@code /}), deepest first, but not
     * one that a path in {@code inUse} lies in (compared without case); returns those kept because they still hold
     * something other than folders of this list, and those emptied that could not be removed.
     */
    static Removed removeEmpty(Path data, Collection<String> folders, Collection<String> inUse) {
        Set<String> used = new HashSet<>();
        for (String path : inUse) {
            for (String folder : parents(path)) {
                used.add(Step.fold(folder));
            }
        }
        List<String> candidates = folders.stream().distinct().filter(f -> !used.contains(Step.fold(f)))
                .sorted(Comparator.comparingInt((String f) -> -depth(f)).thenComparing(f -> f)).toList();
        List<String> notRemoved = new ArrayList<>();
        for (String folder : candidates) {
            Path dir = UpdatePlanner.resolve(data, folder);
            try {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    List<Path> entries = entries(dir);
                    if (entries.stream().allMatch(Folders::clutter)) {
                        for (Path entry : entries) {
                            Files.deleteIfExists(entry);
                        }
                    }
                    Files.delete(dir);
                }
            } catch (DirectoryNotEmptyException | NoSuchFileException e) {
                // kept: it holds something; or gone already
            } catch (IOException | DirectoryIteratorException | UncheckedIOException e) {
                // a folder that cannot be read or removed never stops the switch-over: it is listed
                LOG.warn("Could not remove {}", dir, e);
                notRemoved.add(folder);
            }
        }
        Set<String> folded = new HashSet<>();
        candidates.forEach(f -> folded.add(Step.fold(f)));
        List<String> kept = new ArrayList<>();
        for (String folder : candidates) {
            Path dir = UpdatePlanner.resolve(data, folder);
            if (notRemoved.contains(folder) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (DirectoryStream<Path> entries = open(dir)) {
                for (Path entry : entries) {
                    String child = folder + entry.getFileName() + "/";
                    if (clutter(entry)) {
                        continue;
                    }
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || !folded.contains(Step.fold(child))) {
                        kept.add(folder);
                        break;
                    }
                }
            } catch (IOException | DirectoryIteratorException | UncheckedIOException e) {
                LOG.warn("Could not read {}", dir, e);
                notRemoved.add(folder);
            }
        }
        kept.sort(Comparator.comparing(f -> f.toLowerCase(Locale.ROOT)));
        notRemoved.sort(Comparator.comparing(f -> f.toLowerCase(Locale.ROOT)));
        return new Removed(kept, notRemoved);
    }

    /** {@code .DS_Store}, an AppleDouble {@code ._*}, {@code Thumbs.db} or {@code desktop.ini}: a regular file. */
    static boolean clutter(Path entry) {
        String name = entry.getFileName().toString();
        return (CLUTTER.contains(name.toLowerCase(Locale.ROOT)) || name.startsWith("._"))
                && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> entries(Path dir) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = open(dir)) {
            stream.forEach(entries::add);
        }
        return entries;
    }

    private static DirectoryStream<Path> open(Path dir) throws IOException {
        reading.accept(dir);
        return Files.newDirectoryStream(dir);
    }

    private static int depth(String folder) {
        return (int) folder.chars().filter(ch -> ch == '/').count();
    }
}
