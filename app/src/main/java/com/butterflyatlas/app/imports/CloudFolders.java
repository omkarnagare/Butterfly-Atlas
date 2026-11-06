package com.butterflyatlas.app.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The folders a sync app keeps in step on this device, for the folder browser's shortcuts (one-device design §9), so a
 * library can be put in Google Drive, OneDrive, Dropbox, Box or iCloud Drive without knowing where the sync app keeps it:
 * <ul>
 *   <li>macOS: each folder in {@code ~/Library/CloudStorage} (Google Drive opening at its {@code My Drive}), then iCloud
 *   Drive</li>
 *   <li>Windows: OneDrive where Windows records it ({@code OneDrive}, {@code OneDriveConsumer},
 *   {@code OneDriveCommercial}) and {@code Dropbox} in the home folder; Google Drive is a drive letter, listed with the
 *   drives</li>
 *   <li>Linux: {@code Dropbox} in the home folder</li>
 * </ul>
 * Only folders that exist are listed. Names are plain ("Google Drive"); two accounts of one service add the account.
 */
public final class CloudFolders {

    private static final Logger LOG = LoggerFactory.getLogger(CloudFolders.class);

    public static final String KIND = "cloud";

    private static final Map<String, String> SERVICES = Map.of("GoogleDrive", "Google Drive", "OneDrive", "OneDrive",
            "Dropbox", "Dropbox", "Box", "Box");

    private CloudFolders() {
    }

    /** This device's cloud folders. */
    public static List<FolderBrowser.Root> system(Path home) {
        return find(home, System.getenv(), System.getProperty("os.name", ""));
    }

    /** The cloud folders under {@code home}, given the environment {@code env} and the system's name {@code os}. */
    public static List<FolderBrowser.Root> find(Path home, Map<String, String> env, String os) {
        String system = os.toLowerCase(Locale.ROOT);
        Map<String, FolderBrowser.Root> found = new LinkedHashMap<>();
        if (system.contains("mac")) {
            List<FolderBrowser.Root> storage = cloudStorage(home.resolve("Library").resolve("CloudStorage"));
            storage.forEach(root -> found.putIfAbsent(root.path(), root));
            add(found, "iCloud Drive", home.resolve("Library").resolve("Mobile Documents").resolve("com~apple~CloudDocs"));
            return List.copyOf(found.values());
        }
        List<FolderBrowser.Root> listed = new ArrayList<>();
        Map<String, FolderBrowser.Root> byPath = new LinkedHashMap<>();
        if (system.contains("win")) {
            for (String key : new String[] {"OneDrive", "OneDriveConsumer", "OneDriveCommercial"}) {
                String value = env.get(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                Path folder;
                try {
                    folder = Path.of(value);
                } catch (InvalidPathException e) {
                    continue;
                }
                add(byPath, oneDriveName(folder), folder);
            }
        }
        add(byPath, "Dropbox", home.resolve("Dropbox"));
        listed.addAll(byPath.values());
        listed.sort(Comparator.comparing(FolderBrowser.Root::name, ImportNames.NATURAL));
        return List.copyOf(listed);
    }

    /** macOS's {@code ~/Library/CloudStorage}: one folder per account, {@code <Service>-<account>}. */
    private static List<FolderBrowser.Root> cloudStorage(Path storage) {
        if (!Files.isDirectory(storage)) {
            return List.of();
        }
        List<String[]> accounts = new ArrayList<>(); // service name, account, path
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(storage)) {
            for (Path entry : entries) {
                String folder = entry.getFileName().toString();
                if (!Files.isDirectory(entry) || ImportNames.isHidden(folder)) {
                    continue;
                }
                int dash = folder.indexOf('-');
                String prefix = dash < 0 ? folder : folder.substring(0, dash);
                String account = dash < 0 ? "" : folder.substring(dash + 1);
                String service = SERVICES.getOrDefault(prefix, prefix);
                Path open = entry;
                if (prefix.equals("GoogleDrive") && Files.isDirectory(entry.resolve("My Drive"))) {
                    open = entry.resolve("My Drive");
                }
                accounts.add(new String[] {service, account, open.toString()});
            }
        } catch (IOException | DirectoryIteratorException e) {
            LOG.debug("Could not list the cloud folders in {}", storage, e);
            return List.of();
        }
        Map<String, Integer> perService = new HashMap<>();
        accounts.forEach(a -> perService.merge(a[0], 1, Integer::sum));
        List<FolderBrowser.Root> roots = new ArrayList<>();
        for (String[] a : accounts) {
            boolean plain = perService.get(a[0]) == 1 || a[1].isEmpty() || a[1].equals("Personal")
                    || a[1].equalsIgnoreCase(a[0]);
            roots.add(new FolderBrowser.Root(plain ? a[0] : a[0] + " (" + a[1] + ")", a[2], KIND));
        }
        roots.sort(Comparator.comparing(FolderBrowser.Root::name, ImportNames.NATURAL));
        return roots;
    }

    /** Windows names a work OneDrive's folder {@code OneDrive - <organisation>}. */
    private static String oneDriveName(Path folder) {
        String name = folder.getFileName() == null ? "" : folder.getFileName().toString();
        String marker = "OneDrive - ";
        return name.startsWith(marker) && name.length() > marker.length()
                ? "OneDrive (" + name.substring(marker.length()) + ")" : "OneDrive";
    }

    private static void add(Map<String, FolderBrowser.Root> found, String name, Path folder) {
        if (Files.isDirectory(folder)) {
            found.putIfAbsent(folder.toString(), new FolderBrowser.Root(name, folder.toString(), KIND));
        }
    }
}
