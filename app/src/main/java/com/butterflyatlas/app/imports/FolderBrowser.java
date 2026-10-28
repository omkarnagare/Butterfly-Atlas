package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The in-app folder browser (design §4.1, B1; contract §1): shortcuts and drives, and one folder at a time with its
 * subfolders and photo counts (immediate children only, by name, so listing stays fast). Symbolic links are not
 * listed, as the walk does not follow them. The library's data folder and anything inside it cannot be chosen.
 */
public final class FolderBrowser {

    private static final Logger LOG = LoggerFactory.getLogger(FolderBrowser.class);

    /** Subfolders after this many are listed without counts. */
    static final int COUNTED = 200;

    /** Counting subfolders stops after this long, so a slow disk or share cannot hold a listing up. */
    static final long COUNT_BUDGET_NANOS = 1_500_000_000L;

    public record Root(String name, String path, String kind) {
    }

    public record Crumb(String name, String path) {
    }

    /** {@code folders} and {@code photos} are null when not counted. */
    public record Folder(String name, String path, Integer folders, Integer photos) {
    }

    public record Listing(String path, String name, String parent, List<Crumb> crumbs, List<Folder> folders, int photos,
                          boolean isLibrary, ImportStore.OpenImport openImport) {
    }

    private final Supplier<Optional<Library>> library;
    private final ImportStore store;
    private final Path home;
    private final Supplier<List<Path>> drives;
    private final LongSupplier nanoClock;
    private final List<Path> driveParents;

    public FolderBrowser(Supplier<Optional<Library>> library, ImportStore store, Path home) {
        this(library, store, home, FolderBrowser::systemDrives);
    }

    /** With the drives given (tests). */
    FolderBrowser(Supplier<Optional<Library>> library, ImportStore store, Path home, Supplier<List<Path>> drives) {
        this(library, store, home, drives, System::nanoTime, systemDriveParents());
    }

    /** With the drives and the clock that times counting given (tests). */
    FolderBrowser(Supplier<Optional<Library>> library, ImportStore store, Path home, Supplier<List<Path>> drives,
                  LongSupplier nanoClock) {
        this(library, store, home, drives, nanoClock, systemDriveParents());
    }

    /** With the folders that hold drives given too (tests); a listing only does arithmetic on these. */
    FolderBrowser(Supplier<Optional<Library>> library, ImportStore store, Path home, Supplier<List<Path>> drives,
                  LongSupplier nanoClock, List<Path> driveParents) {
        this.driveParents = driveParents;
        this.nanoClock = nanoClock;
        this.library = library;
        this.store = store;
        this.home = home;
        this.drives = drives;
    }

    /** Home, Pictures, Desktop, Documents (those that exist), then drives and volumes. */
    public List<Root> roots() {
        List<Root> roots = new ArrayList<>();
        if (Files.isDirectory(home)) {
            roots.add(new Root("Home", home.toString(), "home"));
        }
        for (String[] shortcut : new String[][] {{"Pictures", "pictures"}, {"Desktop", "desktop"}, {"Documents", "documents"}}) {
            Path folder = home.resolve(shortcut[0]);
            if (Files.isDirectory(folder)) {
                roots.add(new Root(shortcut[0], folder.toString(), shortcut[1]));
            }
        }
        for (Path drive : drives.get()) {
            if (Files.isDirectory(drive)) {
                roots.add(new Root(displayName(drive), drive.toString(), "drive"));
            }
        }
        return roots;
    }

    /**
     * One folder: its subfolders (sorted by name, naturally; hidden and system folders left out) with their own
     * counts, its photos, where it sits, whether it is the library's folder, and its unfinished import if any.
     */
    public Listing list(String rawPath) {
        Path path = resolve(rawPath);
        if (!Files.isDirectory(path)) {
            throw ImportErrors.folderNotFound();
        }
        List<Path> folders = new ArrayList<>();
        int photos = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                int kind = kind(entry);
                if (kind == FOLDER) {
                    folders.add(entry);
                } else if (kind == PHOTO) {
                    photos++;
                }
            }
        } catch (NoSuchFileException e) {
            throw ImportErrors.folderNotFound();
        } catch (IOException | DirectoryIteratorException e) {
            LOG.debug("Could not list {}", path, e);
            throw ImportErrors.folderUnreadable();
        }
        folders.sort(Comparator.comparing((Path p) -> p.getFileName().toString(), ImportNames.NATURAL));
        long start = nanoClock.getAsLong();
        List<Folder> listed = new ArrayList<>(folders.size());
        for (int i = 0; i < folders.size(); i++) {
            Path folder = folders.get(i);
            boolean inTime = i < COUNTED && nanoClock.getAsLong() - start < COUNT_BUDGET_NANOS;
            int[] counts = inTime ? counts(folder) : null;
            listed.add(new Folder(folder.getFileName().toString(), folder.toString(), counts == null ? null : counts[0],
                    counts == null ? null : counts[1]));
        }
        Optional<Library> lib = library.get();
        boolean isLibrary = lib.isPresent() && isInside(path, lib.get().folder());
        ImportStore.OpenImport open = lib.isPresent() ? store.openImport(path).orElse(null) : null;
        return new Listing(path.toString(), displayName(path), path.getParent() == null ? null : path.getParent().toString(),
                crumbs(path), listed, photos, isLibrary, open);
    }

    /**
     * A typed or pasted path as an absolute, normalised folder path ({@code ~} is the home folder), or 400
     * {@code bad_path} when it is empty or relative.
     */
    Path resolve(String rawPath) {
        return resolve(rawPath, home);
    }

    /** {@link #resolve(String)} with this home folder for {@code ~}. */
    static Path resolve(String rawPath, Path home) {
        if (rawPath == null || rawPath.isBlank()) {
            throw ImportErrors.badPath();
        }
        String text = rawPath.strip();
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).strip(); // Windows "Copy as path"
        }
        Path path;
        try {
            if (text.equals("~")) {
                path = home;
            } else if (text.startsWith("~/") || text.startsWith("~\\")) {
                path = home.resolve(text.substring(2));
            } else {
                path = Path.of(text);
            }
        } catch (InvalidPathException e) {
            throw ImportErrors.badPath();
        }
        if (!path.isAbsolute()) {
            throw ImportErrors.badPath();
        }
        return path.normalize();
    }

    /** Whether {@code path} is {@code folder} or inside it, on disk (links and letter case resolved). */
    static boolean isInside(Path path, Path folder) {
        try {
            return path.toRealPath().startsWith(folder.toRealPath());
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().startsWith(folder.toAbsolutePath().normalize());
        }
    }

    private static final int NOTHING = 0;
    private static final int FOLDER = 1;
    private static final int PHOTO = 2;

    /**
     * What an entry is to the browser, from one read of its attributes (links not followed): a folder to list, a photo
     * to count, or nothing (hidden or system, a link, another kind of file).
     */
    private static int kind(Path entry) {
        BasicFileAttributes attrs;
        try {
            // On Windows these are DosFileAttributes; elsewhere no extra read is made.
            attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return NOTHING;
        }
        String name = entry.getFileName().toString();
        if (attrs.isDirectory()) {
            return ImportNames.isSkippedFolder(name) || isHiddenOrSystem(attrs) ? NOTHING : FOLDER;
        }
        if (ImportNames.isFile(attrs, ImportNames.WINDOWS) && !ImportNames.isHidden(name) && ImportNames.isPhoto(name) && !isHiddenOrSystem(attrs)) {
            return PHOTO;
        }
        return NOTHING;
    }

    /** Windows hidden or system flag; false wherever the attributes carry no such flags. */
    static boolean isHiddenOrSystem(BasicFileAttributes attrs) {
        return attrs instanceof DosFileAttributes dos && (dos.isHidden() || dos.isSystem());
    }

    /** Immediate subfolders and photos of a folder; null when it cannot be read. */
    private static int[] counts(Path folder) {
        int folders = 0;
        int photos = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                int kind = kind(entry);
                if (kind == FOLDER) {
                    folders++;
                } else if (kind == PHOTO) {
                    photos++;
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            return null;
        }
        return new int[] {folders, photos};
    }

    /**
     * From the nearest shortcut or drive that holds this folder (or the file-system root) down to the folder, worked
     * out from the path alone (Windows drives are the path's root).
     */
    private List<Crumb> crumbs(Path path) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(home);
        for (String shortcut : new String[] {"Pictures", "Desktop", "Documents"}) {
            candidates.add(home.resolve(shortcut));
        }
        for (Path parent : driveParents) { // no file-system calls: a dead mount must not stall a listing
            if (path.startsWith(parent) && path.getNameCount() > parent.getNameCount()) {
                candidates.add(parent.resolve(path.getName(parent.getNameCount()).toString()));
            }
        }
        Path start = path.getRoot();
        for (Path candidate : candidates) {
            if (path.startsWith(candidate) && (start == null || candidate.getNameCount() > start.getNameCount())) {
                start = candidate;
            }
        }
        List<Crumb> crumbs = new ArrayList<>();
        for (Path p = path; p != null; p = p.getParent()) {
            crumbs.add(0, new Crumb(displayName(p), p.toString()));
            if (p.equals(start)) {
                break;
            }
        }
        return crumbs;
    }

    private static String displayName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    /** Folders whose subfolders are drives: macOS volumes; Linux removable media and /mnt. None on Windows. */
    private static List<Path> systemDriveParents() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of();
        }
        if (os.contains("mac")) {
            return List.of(Path.of("/Volumes"));
        }
        String user = System.getProperty("user.name", "");
        return List.of(Path.of("/media", user), Path.of("/run/media", user), Path.of("/mnt"));
    }

    /** Windows drive letters; macOS volumes (not the link to the system volume); Linux removable media and /mnt. */
    private static List<Path> systemDrives() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> drives = new ArrayList<>();
        if (os.contains("win")) {
            FileSystems.getDefault().getRootDirectories().forEach(drives::add);
            return drives;
        }
        for (Path parent : systemDriveParents()) {
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
                for (Path entry : entries) {
                    // macOS lists the system volume as a link to "/"; it is already reachable as Home.
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && !ImportNames.isHidden(entry.getFileName().toString())) {
                        drives.add(entry);
                    }
                }
            } catch (IOException | DirectoryIteratorException e) {
                LOG.debug("Could not list drives in {}", parent, e);
            }
        }
        drives.sort(Comparator.comparing(Path::toString, ImportNames.NATURAL));
        return drives;
    }
}
