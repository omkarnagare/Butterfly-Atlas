package com.butterflyatlas.app.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The folder browser's cloud folders (one-device design §9): the folders that Google Drive, OneDrive, Dropbox, Box and
 * iCloud Drive keep in step on this device, named plainly, so a library can be put in one without knowing where the sync
 * app keeps it. Only folders that exist are listed.
 */
class CloudFoldersTest {

    @TempDir
    Path home;

    private Path folder(String first, String... more) throws Exception {
        return Files.createDirectories(home.resolve(Path.of(first, more)));
    }

    /**
     * A link at {@code link} to the folder {@code target}: a symbolic link, or on Windows, where only an administrator or
     * Developer Mode may make one, a directory junction ({@code mklink /J}), which anyone may make.
     */
    private static Path linkToFolder(Path link, Path target) throws Exception {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                abort("symbolic links can't be made here: " + e.getMessage());
            }
        }
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        mklink.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (mklink.waitFor() != 0) {
            abort("neither a symbolic link nor a junction can be made here");
        }
        return link;
    }

    private static FolderBrowser.Root cloud(String name, Path path) {
        return new FolderBrowser.Root(name, path.toString(), "cloud");
    }

    @Test
    void onMacOsTheSyncAppsFoldersInCloudStorageAreListedWithGoogleDriveOpeningAtMyDrive() throws Exception {
        Path myDrive = folder("Library", "CloudStorage", "GoogleDrive-omkar@example.com", "My Drive");
        Path oneDrive = folder("Library", "CloudStorage", "OneDrive-Personal");
        Path dropbox = folder("Library", "CloudStorage", "Dropbox");
        Path box = folder("Library", "CloudStorage", "Box-Box");
        Path icloud = folder("Library", "Mobile Documents", "com~apple~CloudDocs");

        assertThat(CloudFolders.find(home, Map.of(), "Mac OS X")).containsExactly(
                cloud("Box", box), cloud("Dropbox", dropbox), cloud("Google Drive", myDrive), cloud("OneDrive", oneDrive),
                cloud("iCloud Drive", icloud));
    }

    @Test
    void twoAccountsOfOneServiceAreToldApartByTheAccount() throws Exception {
        Path personal = folder("Library", "CloudStorage", "GoogleDrive-me@example.com", "My Drive");
        Path work = folder("Library", "CloudStorage", "GoogleDrive-me@work.example", "My Drive");
        Path oneDrive = folder("Library", "CloudStorage", "OneDrive-Personal");
        Path oneDriveWork = folder("Library", "CloudStorage", "OneDrive-Acme Ltd");

        assertThat(CloudFolders.find(home, Map.of(), "Mac OS X")).containsExactly(
                cloud("Google Drive (me@example.com)", personal), cloud("Google Drive (me@work.example)", work),
                cloud("OneDrive", oneDrive), cloud("OneDrive (Acme Ltd)", oneDriveWork));
    }

    @Test
    void aGoogleDriveWithoutMyDriveYetOpensAtItsOwnFolder() throws Exception {
        Path drive = folder("Library", "CloudStorage", "GoogleDrive-me@example.com");

        assertThat(CloudFolders.find(home, Map.of(), "Mac OS X")).containsExactly(cloud("Google Drive", drive));
    }

    @Test
    void onWindowsOneDriveComesFromItsSettingAndDropboxFromHome() throws Exception {
        Path oneDrive = folder("OneDrive");
        Path oneDriveWork = folder("OneDrive - Acme Ltd");
        Path dropbox = folder("Dropbox");

        assertThat(CloudFolders.find(home, Map.of("OneDrive", oneDrive.toString(), "OneDriveConsumer", oneDrive.toString(),
                "OneDriveCommercial", oneDriveWork.toString()), "Windows 11")).containsExactly(
                cloud("Dropbox", dropbox), cloud("OneDrive", oneDrive), cloud("OneDrive (Acme Ltd)", oneDriveWork));
    }

    @Test
    void aSettingForAFolderThatIsGoneListsNothing() {
        assertThat(CloudFolders.find(home, Map.of("OneDrive", home.resolve("missing").toString()), "Windows 11")).isEmpty();
    }

    @Test
    void onLinuxDropboxInHomeIsListed() throws Exception {
        Path dropbox = folder("Dropbox");

        assertThat(CloudFolders.find(home, Map.of(), "Linux")).containsExactly(cloud("Dropbox", dropbox));
    }

    @Test
    void aHomeWithoutCloudFoldersListsNone() {
        assertThat(CloudFolders.find(home, Map.of(), "Mac OS X")).isEmpty();
        assertThat(CloudFolders.find(home, Map.of(), "Windows 11")).isEmpty();
        assertThat(CloudFolders.find(home, Map.of(), "Linux")).isEmpty();
    }

    @Test
    void theFolderBrowserListsThemAfterDocumentsAndBeforeDrives() throws Exception {
        Files.createDirectories(home.resolve("Documents"));
        Path dropbox = folder("Library", "CloudStorage", "Dropbox");
        Path drive = Files.createDirectories(home.resolve("Volumes").resolve("Photos SSD"));
        FolderBrowser browser = new FolderBrowser(java.util.Optional::empty, null, home, () -> List.of(drive),
                System::nanoTime, List.of(), h -> CloudFolders.find(h, Map.of(), "Mac OS X"));

        assertThat(browser.roots()).extracting(FolderBrowser.Root::kind)
                .containsExactly("home", "documents", "cloud", "drive");
        assertThat(browser.roots().get(2)).isEqualTo(cloud("Dropbox", dropbox));
    }
    // Which cloud folder a library folder is in (cloud-library-folder design §2): paths compared, nothing listed.

    private String service(Path folder, String os) {
        return CloudFolders.service(folder, home, Map.of(), os, p -> null);
    }

    @Test
    void onMacOsAFolderInCloudStorageIsInItsServiceWithoutTheAccount() throws Exception {
        Path myDrive = folder("Library", "CloudStorage", "GoogleDrive-me@example.com", "My Drive");
        Path shared = folder("Library", "CloudStorage", "GoogleDrive-me@example.com", "Shared drives", "Team");

        assertThat(service(myDrive.resolve("Butterfly_Atlas"), "Mac OS X")).isEqualTo("Google Drive");
        assertThat(service(shared.resolve("Butterfly_Atlas"), "Mac OS X")).as("outside My Drive too").isEqualTo("Google Drive");
        assertThat(service(home.resolve("Library/CloudStorage/OneDrive-Acme Ltd/Photos"), "Mac OS X")).isEqualTo("OneDrive");
        assertThat(service(home.resolve("Library/CloudStorage/Dropbox/Butterfly_Atlas"), "Mac OS X")).isEqualTo("Dropbox");
        assertThat(service(home.resolve("Library/CloudStorage/Box-Box/A"), "Mac OS X")).isEqualTo("Box");
        assertThat(service(home.resolve("Library/CloudStorage/pCloud-me/A"), "Mac OS X")).as("another service: its prefix")
                .isEqualTo("pCloud");
        assertThat(service(home.resolve("Library/CloudStorage"), "Mac OS X")).as("the folder that holds them").isNull();
    }

    @Test
    void onMacOsAFolderInICloudDriveIsInICloudDrive() throws Exception {
        Path icloud = folder("Library", "Mobile Documents", "com~apple~CloudDocs");

        assertThat(service(icloud.resolve("Butterfly_Atlas"), "Mac OS X")).isEqualTo("iCloud Drive");
    }

    @Test
    void aFolderReachedThroughALinkIsInTheCloudFolderTheLinkLeadsTo() throws Exception {
        Path dropbox = folder("Library", "CloudStorage", "Dropbox");
        Path link = linkToFolder(home.resolve("Shortcut"), dropbox);
        Path realHome = home.toRealPath();

        assertThat(CloudFolders.service(link.resolve("Butterfly_Atlas"), realHome, Map.of(), "Mac OS X", p -> null))
                .isEqualTo("Dropbox");
    }

    @Test
    void onWindowsOneDriveComesFromItsSettingsAndDropboxFromHome() throws Exception {
        Path work = folder("OneDrive - Acme Ltd");
        Map<String, String> env = Map.of("OneDriveCommercial", work.toString());

        assertThat(CloudFolders.service(work.resolve("Butterfly_Atlas"), home, env, "Windows 11", p -> null)).isEqualTo("OneDrive");
        assertThat(CloudFolders.service(home.resolve("Dropbox").resolve("A"), home, env, "Windows 11", p -> null))
                .isEqualTo("Dropbox");
        assertThat(CloudFolders.service(home.resolve("Documents"), home, env, "Windows 11", p -> null)).isNull();
    }

    @Test
    void onWindowsGoogleDrivesDriveLetterIsToldByItsVolumesNameOrType() {
        Path g = home.resolve("G").resolve("My Drive").resolve("Butterfly_Atlas");

        assertThat(CloudFolders.service(g, home, Map.of(), "Windows 11", p -> new CloudFolders.Store("Google Drive", "FAT32")))
                .isEqualTo("Google Drive");
        assertThat(CloudFolders.service(g, home, Map.of(), "Windows 11", p -> new CloudFolders.Store("", "GoogleDriveFS")))
                .isEqualTo("Google Drive");
        assertThat(CloudFolders.service(g, home, Map.of(), "Windows 11", p -> new CloudFolders.Store("Photos", "NTFS"))).isNull();
        assertThat(CloudFolders.service(g, home, Map.of(), "Mac OS X", p -> new CloudFolders.Store("Google Drive", "x")))
                .as("a drive's name counts on Windows only").isNull();
    }

    @Test
    void onLinuxDropboxInHomeIsACloudFolder() {
        assertThat(service(home.resolve("Dropbox").resolve("Butterfly_Atlas"), "Linux")).isEqualTo("Dropbox");
    }

    @Test
    void aNormalFolderIsInNoCloudFolder() throws Exception {
        folder("Library", "CloudStorage", "Dropbox");
        Path pictures = folder("Pictures", "Butterfly_Atlas");

        assertThat(service(pictures, "Mac OS X")).isNull();
        assertThat(service(pictures, "Windows 11")).isNull();
        assertThat(service(pictures, "Linux")).isNull();
        assertThat(service(home.resolve("Dropboxes"), "Linux")).as("a name that only starts the same").isNull();
    }
}
