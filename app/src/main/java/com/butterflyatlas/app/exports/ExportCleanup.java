package com.butterflyatlas.app.exports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cancel with Remove (4b contract §0, E4): deletes only the files this export recorded as written (its photos and its
 * species list) that still match what was recorded (size, and time within {@link #MTIME_SLACK_MS}), and the
 * {@code .part} files of its targets, then the folders it made that are left empty, bottom up (the export folder last).
 * Never anything else, and never through a link: every folder from the export folder down must resolve to itself
 * ({@code toRealPath}) and not be a symbolic link, or the file is kept and counted as not removed. In any doubt (an
 * attribute or a real path that cannot be read) it keeps. The export folder is checked once per Remove, and each folder
 * below it once (its verdict kept for the rest of the call).
 */
public final class ExportCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(ExportCleanup.class);

    /** How far a written file's time may move and still be ours: FAT, exFAT and network shares keep 2 s (after a replug). */
    static final long MTIME_SLACK_MS = 2_000;
    private static final boolean WINDOWS = FileSystems.getDefault().getSeparator().equals("\\");
    /** How long a folder's verdict is kept within one Remove before it is read again. */
    private static final long VERDICT_NANOS = Duration.ofSeconds(1).toNanos();
    /** Told of each real path a Remove's folder checks read: a test hook (they count them). */
    private static volatile Runnable realPathRead = () -> { };

    private ExportCleanup() {
    }

    /** Test-only: {@code hook} runs on each real path a folder check reads ({@code null}: nothing does). */
    static void onRealPathRead(Runnable hook) {
        realPathRead = hook == null ? () -> { } : hook;
    }

    /**
     * Removes them; returns how many files were kept: written files that changed, whose time was not recorded, that sit
     * behind a link or cannot be read, files at targets of photos still to copy (never this export's), or every
     * written file when the export folder cannot be reached (nothing can be said to be gone).
     */
    public static int remove(Path folder, List<ExportStore.Written> written, List<String> targets, List<String> pending,
                      ExportStore.Written csv) {
        if (!exportFolder(folder)) {
            return written.size() + (csv == null ? 0 : 1);
        }
        Chains chains = new Chains(folder);
        int notRemoved = 0;
        List<ExportStore.Written> files = new ArrayList<>(written);
        if (csv != null) {
            files.add(csv);
        }
        for (ExportStore.Written file : files) {
            if (!removeIfSame(chains, ExportCopier.resolve(folder, file.targetRel()), file)) {
                notRemoved++;
            }
        }
        for (String rel : pending) {
            if (!Files.notExists(ExportCopier.resolve(folder, rel), LinkOption.NOFOLLOW_LINKS)) {
                notRemoved++; // a file this export did not record as written: kept
            }
        }
        removeParts(chains, targets);
        Set<Path> folders = new HashSet<>();
        for (String rel : targets) {
            Path target = ExportCopier.resolve(folder, rel);
            for (Path p = target.getParent(); p != null && p.startsWith(folder) && !p.equals(folder); p = p.getParent()) {
                folders.add(p);
            }
        }
        List<Path> bottomUp = new ArrayList<>(folders);
        bottomUp.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path p : bottomUp) {
            if (chains.real(p)) {
                deleteEmpty(p); // a link (or anything that does not resolve to itself) is never deleted
            }
        }
        if (exportFolder(folder)) {
            deleteEmpty(folder);
        }
        return notRemoved;
    }

    /**
     * Cancel with Keep: removes only the {@code .part} files of the export's targets (regular files, reached through real
     * folders, as Remove does); the copies stay.
     */
    public static void removeParts(Path folder, List<String> targets) {
        if (exportFolder(folder)) {
            removeParts(new Chains(folder), targets);
        }
    }

    private static void removeParts(Chains chains, List<String> targets) {
        for (String rel : targets) {
            Path part = ExportCopier.part(ExportCopier.resolve(chains.folder, rel));
            BasicFileAttributes attrs = attributes(part);
            if (attrs != null && attrs.isRegularFile() && chains.safe(part)) {
                delete(part);
            }
        }
    }

    /**
     * Deletes a written file only while it is ours: reached through real folders, same size, time within the slack. A
     * file already gone counts as removed when the nearest of its folders still there is a real one of the export (its
     * own folder may be gone too).
     */
    private static boolean removeIfSame(Chains chains, Path file, ExportStore.Written record) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            for (Path p = file.getParent(); p != null && p.startsWith(chains.folder); p = p.getParent()) {
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                    return chains.real(p); // a link there (even a dangling one) is not ours to say so
                }
            }
            return false;
        } catch (IOException e) {
            return false; // cannot tell: kept
        }
        boolean isFile = attrs.isRegularFile() || (WINDOWS && attrs.isOther() && !attrs.isDirectory()); // or a placeholder
        boolean same = isFile && !attrs.isSymbolicLink() && attrs.size() == record.bytes() && record.mtime() != null
                && Math.abs(attrs.lastModifiedTime().toMillis() - record.mtime()) <= MTIME_SLACK_MS;
        return same && chains.safe(file) && delete(file);
    }

    /**
     * The verdicts of {@link #realChain} within one Remove: each folder from the export folder down is read once, and
     * again after {@link #VERDICT_NANOS}. Keeping a verdict that long is safe: a folder swapped for a link in that moment
     * could only lead to files with the size and time recorded for this export's copies (nothing else is deleted), and
     * the per-file check it replaces had the same gap between its check and the delete.
     */
    private static final class Chains {

        private record Verdict(Optional<Path> real, long at) {
        }

        private final Path folder;
        private final Map<Path, Verdict> reals = new HashMap<>();

        Chains(Path folder) {
            this.folder = folder;
        }

        boolean safe(Path file) {
            return file.normalize().startsWith(folder) && !file.normalize().equals(folder) && real(file.getParent());
        }

        /** As {@link #realChain}, for the export folder checked at the start of the Remove. */
        boolean real(Path to) {
            return to.normalize().startsWith(folder) && realPath(to.normalize()).isPresent();
        }

        /** The real path of a folder of the export reached only through real folders; empty when it is not one. */
        private Optional<Path> realPath(Path at) {
            Verdict known = reals.get(at);
            if (known != null && System.nanoTime() - known.at() < VERDICT_NANOS) {
                return known.real();
            }
            Optional<Path> verdict;
            if (at.equals(folder)) {
                verdict = realPathOf(folder);
            } else {
                Optional<Path> parent = realPath(at.getParent());
                verdict = Optional.empty();
                if (parent.isPresent()) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(at, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        Path expected = parent.get().resolve(at.getFileName().toString());
                        realPathRead.run();
                        if (!attrs.isSymbolicLink() && at.toRealPath().equals(expected)) {
                            verdict = Optional.of(expected);
                        }
                    } catch (IOException e) {
                        // cannot tell: not ours
                    }
                }
            }
            reals.put(at, new Verdict(verdict, System.nanoTime()));
            return verdict;
        }

        private static Optional<Path> realPathOf(Path folder) {
            try {
                realPathRead.run();
                return Optional.of(folder.toRealPath());
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * Whether {@code file} is inside the export folder by its path and every folder from the export folder down to its
     * parent is a real folder of it.
     */
    public static boolean safe(Path folder, Path file) {
        return file.normalize().startsWith(folder) && !file.normalize().equals(folder) && realChain(folder, file.getParent());
    }

    /**
     * Whether the export folder, and every folder from it down to {@code to}, is a real folder: not a symbolic link (read
     * without following links) and resolving ({@code toRealPath}) to exactly the export folder's real path with the same
     * names below it, so a junction or a link anywhere on the way is never gone through.
     */
    private static boolean realChain(Path folder, Path to) {
        if (!exportFolder(folder) || !to.normalize().startsWith(folder)) {
            return false;
        }
        try {
            Path real = folder.toRealPath();
            Path at = folder;
            for (Path name : folder.relativize(to)) {
                if (name.toString().isEmpty()) {
                    continue;
                }
                at = at.resolve(name);
                real = real.resolve(name);
                BasicFileAttributes attrs = Files.readAttributes(at, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || !at.toRealPath().equals(real)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The export folder: a folder, not a symbolic link, and resolving to itself inside its (real) parent (the copy's
     * test that it can be reached: a folder swapped for a link or a junction is not).
     */
    public static boolean exportFolder(Path folder) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || !Files.isDirectory(folder)) {
                return false;
            }
            Path parent = folder.toAbsolutePath().getParent();
            return parent != null && folder.toRealPath().equals(parent.toRealPath().resolve(folder.getFileName()));
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteEmpty(Path folder) {
        try {
            Files.deleteIfExists(folder);
        } catch (DirectoryNotEmptyException e) {
            // holds something this export did not write: kept
        } catch (IOException e) {
            LOG.debug("Could not remove the folder {}", folder, e);
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    /** True when the file is gone (removed now, or not there). */
    private static boolean delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOG.debug("Could not remove {}", file, e);
            return false;
        }
    }
}
