package com.butterflyatlas.app.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
