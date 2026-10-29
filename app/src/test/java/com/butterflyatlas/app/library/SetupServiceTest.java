package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupServiceTest {

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();

    private ConfigStore config() {
        return new ConfigStore(home.resolve("config"));
    }

    private SetupService service(LibraryHolder libraries) {
        return new SetupService(config(), libraries, TestBundles.fixture(), TestPlaces.bundles(), home, TestBundles.CLOCK);
    }

    private static int count(LibraryHolder libraries, String sql) {
        return libraries.get().read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    @Test
    void isNotConfiguredAtFirst() {
        SetupService.Status status = service(holder).status();

        assertThat(status.configured()).isFalse();
        assertThat(status.suggestedFolder()).isEqualTo(home.resolve("Documents").resolve("Butterfly_Atlas").toString());
        assertThat(status.dataFolder()).isNull();
        assertThat(status.problem()).isNull();
        assertThatThrownBy(holder::get).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.SETUP_REQUIRED));
        assertThatThrownBy(holder::library).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.SETUP_REQUIRED));
    }

    @Test
    void setupCreatesTheLibraryAndRemembersIt() {
        Path folder = home.resolve("Atlas");

        SetupService.Status status = service(holder).setup(folder.toString());

        assertThat(status.configured()).isTrue();
        assertThat(status.dataFolder()).isEqualTo(folder.toString());
        assertThat(folder.resolve("atlas.db")).exists();
        assertThat(holder.library().folder()).isEqualTo(folder);
        assertThat(config().load()).contains(new AppConfig(folder.toString()));
        assertThat(count(holder, "SELECT COUNT(*) FROM taxon")).isEqualTo(16);
    }

    @Test
    void choosingALibraryKeepsASavedTheme() throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(config().file(), "{\"theme\": \"dark\"}");
        Path folder = home.resolve("Atlas");
        SetupService setup = service(holder);

        setup.setup(folder.toString());

        assertThat(setup.theme()).isEqualTo("dark");
        assertThat(config().load()).contains(new AppConfig(folder.toString(), "dark"));
    }

    @Test
    void startupReopensTheRememberedLibraryWithoutReloadingIt() {
        Path folder = home.resolve("Atlas");
        service(new LibraryHolder()).setup(folder.toString());

        service(holder).startup(Optional.empty());

        assertThat(service(holder).status().configured()).isTrue();
        assertThat(service(holder).status().problem()).isNull();
        assertThat(holder.current()).hasValueSatisfying(l -> assertThat(l.folder()).isEqualTo(folder));
        assertThat(count(holder, "SELECT COUNT(*) FROM taxonomy_state")).isEqualTo(1);
    }

    @Test
    void startupExplainsAMissingLibraryFolder() {
        config().save(new AppConfig(home.resolve("Unplugged drive").toString()));
        SetupService setup = service(holder);

        setup.startup(Optional.empty());

        assertThat(setup.status().configured()).isFalse();
        assertThat(setup.status().problem()).startsWith("Butterfly Atlas could not find your library at ");
    }

    @Test
    void startupOverrideUsesTheGivenFolderWithoutRememberingIt() {
        SetupService setup = service(holder);

        setup.startup(Optional.of(home.resolve("Dev library")));

        assertThat(setup.status().configured()).isTrue();
        assertThat(config().file()).doesNotExist();
    }

    @Test
    void refusesAFolderWhoseAtlasDbIsNotALibrary() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Other"));
        Files.writeString(folder.resolve("atlas.db"), "not a database");

        assertThatThrownBy(() -> service(holder).setup(folder.toString()))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
                    assertThat(e.getMessage()).startsWith("Butterfly Atlas cannot open the library in that folder");
                });
        assertThat(holder.current()).isEmpty();
    }

    @Test
    void startupTreatsARelativeRememberedPathAsMissing() {
        config().save(new AppConfig("relative/folder"));
        SetupService setup = service(holder);

        setup.startup(Optional.empty());

        assertThat(setup.status().configured()).isFalse();
        assertThat(setup.status().problem()).startsWith("Butterfly Atlas could not find your library at ");
    }

    @Test
    void rejectsBlankAndRelativePaths() {
        SetupService setup = service(holder);

        assertThatThrownBy(() -> setup.setup("  ")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.getMessage()).isEqualTo("Choose a folder for your library.");
        });
        assertThatThrownBy(() -> setup.setup("relative/folder")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).startsWith("Enter the full path of a folder"));
    }

    @Test
    void rejectsAFile() throws Exception {
        Path file = Files.writeString(home.resolve("notes.txt"), "x");

        assertThatThrownBy(() -> service(holder).setup(file.toString()))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("That is a file, not a folder. Choose a folder."));
    }

    @Test
    void expandsTheHomeShortcut() {
        SetupService.Status status = service(holder).setup("~/Atlas Library");

        assertThat(status.dataFolder()).isEqualTo(home.resolve("Atlas Library").toString());
    }

    @Test
    void refusesASecondSetup() {
        SetupService setup = service(holder);
        setup.setup(home.resolve("Atlas").toString());

        assertThatThrownBy(() -> setup.setup(home.resolve("Other").toString()))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.ALREADY_CONFIGURED));
    }

    @Test
    void inspectAsGivenDescribesTheFolderItself() throws Exception {
        Path folder = home.resolve("Atlas");
        service(new LibraryHolder()).setup(folder.toString());
        Path file = Files.writeString(home.resolve("notes.txt"), "x");
        SetupService setup = service(holder);

        assertThat(setup.inspect(folder.toString())).isEqualTo(check(folder, folder, SetupService.EXISTING));
        assertThat(setup.inspect(home.resolve("New").toString(), true))
                .isEqualTo(check(home.resolve("New"), home.resolve("New"), SetupService.CREATE));
        assertThat(setup.inspect(file.toString(), true)).isEqualTo(check(file, null, SetupService.NOT_A_FOLDER));
        assertThat(setup.inspect("relative", true))
                .isEqualTo(new SetupService.FolderCheck("relative", false, null, SetupService.RELATIVE, null));
    }

    @Test
    void inspectInsideKeepsTheLibraryInAButterflyAtlasFolder() throws Exception {
        SetupService setup = service(holder);
        Path documents = Files.createDirectories(home.resolve("Documents"));
        assertThat(setup.inspect(documents.toString(), false))
                .isEqualTo(check(documents, documents.resolve("Butterfly_Atlas"), SetupService.CREATE));

        Files.createDirectories(documents.resolve("Butterfly_Atlas"));
        assertThat(setup.inspect(documents.toString(), false)).as("an empty Butterfly_Atlas is used")
                .isEqualTo(check(documents, documents.resolve("Butterfly_Atlas"), SetupService.CREATE));

        Files.writeString(documents.resolve("Butterfly_Atlas").resolve("other.txt"), "x");
        assertThat(setup.inspect(documents.toString(), false))
                .isEqualTo(check(documents, documents.resolve("Butterfly_Atlas"), SetupService.TAKEN));

        Path drive = Files.createDirectories(home.resolve("Drive"));
        Files.writeString(drive.resolve("Butterfly_Atlas"), "a file");
        assertThat(setup.inspect(drive.toString(), false))
                .isEqualTo(check(drive, drive.resolve("Butterfly_Atlas"), SetupService.TAKEN));

        assertThat(setup.inspect(home.resolve("Missing").toString(), false))
                .isEqualTo(check(home.resolve("Missing"), null, SetupService.NOT_FOUND));
        Path file = Files.writeString(home.resolve("notes.txt"), "x");
        assertThat(setup.inspect(file.toString(), false)).isEqualTo(check(file, null, SetupService.NOT_A_FOLDER));
    }

    @Test
    void inspectInsideFindsALibraryInTheFolderOrInItsButterflyAtlasFolder() {
        Path library = home.resolve("Old").resolve("Butterfly_Atlas");
        service(new LibraryHolder()).setup(library.toString());
        SetupService setup = service(holder);

        assertThat(setup.inspect(home.resolve("Old").toString(), false))
                .isEqualTo(check(home.resolve("Old"), library, SetupService.EXISTING));
        assertThat(setup.inspect(library.toString(), false)).as("the library folder itself")
                .isEqualTo(check(library, library, SetupService.EXISTING));
    }

    @Test
    void inspectInsideSaysWhenTheFolderCannotBeWritten() throws Exception {
        Path locked = Files.createDirectories(home.resolve("Locked"));
        org.junit.jupiter.api.Assumptions.assumeTrue(locked.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(locked), "running as a user who can write anywhere");
            assertThat(service(holder).inspect(locked.toString(), false))
                    .isEqualTo(check(locked, locked.resolve("Butterfly_Atlas"), SetupService.NOT_WRITABLE));
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void setupInsideCreatesTheLibraryInAButterflyAtlasFolder() throws Exception {
        Path documents = Files.createDirectories(home.resolve("Documents"));

        SetupService.Status status = service(holder).setup(documents.toString(), false);

        Path library = documents.resolve("Butterfly_Atlas");
        assertThat(status.dataFolder()).isEqualTo(library.toString());
        assertThat(library.resolve("atlas.db")).exists();
        assertThat(config().load()).contains(new AppConfig(library.toString()));
    }

    @Test
    void setupInsideOpensTheLibraryThatIsThere() {
        Path library = home.resolve("Old").resolve("Butterfly_Atlas");
        service(new LibraryHolder()).setup(library.toString());

        assertThat(service(holder).setup(home.resolve("Old").toString(), false).dataFolder()).isEqualTo(library.toString());
        assertThat(library.resolve("Butterfly_Atlas")).doesNotExist();
    }

    @Test
    void setupInsideRefusesATakenOrMissingFolder() throws Exception {
        Path documents = Files.createDirectories(home.resolve("Documents"));
        Files.writeString(Files.createDirectories(documents.resolve("Butterfly_Atlas")).resolve("other.txt"), "x");

        assertThatThrownBy(() -> service(holder).setup(documents.toString(), false))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.getMessage()).isEqualTo(
                        documents.resolve("Butterfly_Atlas") + " already exists and isn't a Butterfly Atlas library."
                                + " Choose another folder."));
        assertThatThrownBy(() -> service(holder).setup(home.resolve("Missing").toString(), false))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("That folder can't be found. Choose another folder."));
        assertThat(home.resolve("Missing")).doesNotExist();
        assertThat(holder.current()).isEmpty();
    }

    private static SetupService.FolderCheck check(Path picked, Path library, String kind) {
        return new SetupService.FolderCheck(picked.toString(), true, library == null ? null : library.toString(), kind, null);
    }

    @Test
    void choosingAFolderThatHoldsALibraryOpensIt() {
        Path folder = home.resolve("Atlas");
        service(new LibraryHolder()).setup(folder.toString());

        service(holder).setup(folder.toString());

        assertThat(count(holder, "SELECT COUNT(*) FROM taxonomy_state")).isEqualTo(1);
        assertThat(count(holder, "SELECT COUNT(*) FROM taxon")).isEqualTo(16);
    }

    @Test
    void listenersHearWhenALibraryOpens() {
        List<Path> opened = new ArrayList<>();
        holder.whenOpened(library -> opened.add(library.folder()));

        service(holder).setup(home.resolve("Atlas").toString());

        assertThat(opened).containsExactly(home.resolve("Atlas"));

        LibraryHolder later = new LibraryHolder();
        later.whenOpened(library -> opened.add(library.folder()));
        service(later).startup(Optional.empty());
        assertThat(opened).containsExactly(home.resolve("Atlas"), home.resolve("Atlas"));
    }

    /** A library on the fixture places that remembers its folder, as the app leaves it. */
    private Path rememberedOnFirstList() {
        Path folder = home.resolve("Atlas");
        service(new LibraryHolder()).setup(folder.toString());
        return folder;
    }

    private SetupService service(LibraryHolder libraries, com.butterflyatlas.app.places.PlaceBundles places) {
        return new SetupService(config(), libraries, TestBundles.fixture(), places, home, TestBundles.CLOCK);
    }

    private static void exec(Path folder, String sql) {
        com.butterflyatlas.app.db.Database.open(folder.resolve("atlas.db")).write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void aPlaceUpdateStepThatFailsLeavesTheLibraryClosedWithThePlainReasonAndTryAgainOpensIt() {
        Path folder = rememberedOnFirstList();
        exec(folder, "CREATE TRIGGER fail_step BEFORE INSERT ON place_update BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");
        SetupService setup = service(holder, com.butterflyatlas.app.testing.UpdatePlaces.bundles(com.butterflyatlas.app.testing.UpdatePlaces.fixture(),
                com.butterflyatlas.app.testing.UpdatePlaces.fixtureNext()));

        setup.startup(Optional.empty());

        SetupService.Status status = setup.status();
        assertThat(status.configured()).isFalse();
        assertThat(status.retry()).isTrue();
        assertThat(status.dataFolder()).as("the screen says the photos are still there").isEqualTo(folder.toString());
        assertThat(status.problem()).as("no raw error text").isEqualTo("Place names couldn't be updated: something unexpected"
                + " stopped it. If this keeps happening, contact the Butterfly Atlas developers with what you did just before. Your"
                + " library is unchanged.");
        assertThat(holder.current()).isEmpty();

        assertThat(setup.retry().problem()).as("still failing: said again").startsWith("Place names couldn't be updated:");
        exec(folder, "DROP TRIGGER fail_step");
        SetupService.Status retried = setup.retry();

        assertThat(retried.configured()).isTrue();
        assertThat(retried.problem()).isNull();
        assertThat(retried.retry()).isFalse();
        assertThat(holder.library().placeSteps()).containsExactly("2026-11-01");
        assertThatThrownBy(setup::retry).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("not_failed"));
    }

    @Test
    void aLibraryOnPlaceDataFromANewerAppSaysWhatToInstall() {
        Path folder = rememberedOnFirstList();
        exec(folder, "INSERT INTO place_state (bundle_version, bundle_sha256, applied_at) VALUES ('2027-02-01', 'x', 'now')");
        SetupService setup = service(holder);

        setup.startup(Optional.empty());

        assertThat(setup.status().problem()).isEqualTo("This library uses place data from a newer version of Butterfly"
                + " Atlas (2027-02-01). Install that version or a newer one to open it.");
        assertThat(setup.status().retry()).as("Try again can't help").isFalse();
        assertThatThrownBy(() -> service(new LibraryHolder()).retry()).as("nothing failed in that service")
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("not_failed"));
    }

    @Test
    void aLaterStepFailingAfterAnEarlierOneSaysHowFarTheNamesGot() {
        Path folder = rememberedOnFirstList();
        com.butterflyatlas.app.testing.UpdatePlaces.Built v2 = com.butterflyatlas.app.testing.UpdatePlaces.fixtureNext();
        com.butterflyatlas.app.testing.UpdatePlaces.Built v3 = com.butterflyatlas.app.testing.UpdatePlaces.derive(v2,
                "2027-02-01").rename("P12", "Alpha Rise").build();
        exec(folder, "CREATE TRIGGER fail_step BEFORE INSERT ON place_update WHEN NEW.version = '2027-02-01'"
                + " BEGIN SELECT RAISE(ABORT, 'nope'); END");
        SetupService setup = service(holder, com.butterflyatlas.app.testing.UpdatePlaces.bundles(
                com.butterflyatlas.app.testing.UpdatePlaces.fixture(), v2, v3));

        setup.startup(Optional.empty());

        assertThat(setup.status().problem()).isEqualTo("Place names were updated to 2026-11-01, but the update to 2027-02-01"
                + " couldn't be applied: something unexpected stopped it. If this keeps happening, contact the Butterfly"
                + " Atlas developers with what you did just before. Nothing of that last update was kept; choose Try again.");
        assertThat(setup.status().retry()).isTrue();

        Path other = home.resolve("Other");
        service(new LibraryHolder()).setup(other.toString());
        exec(other, "CREATE TRIGGER fail_step BEFORE INSERT ON place_update WHEN NEW.version = '2027-02-01'"
                + " BEGIN SELECT RAISE(ABORT, 'nope'); END");
        assertThatThrownBy(() -> service(new LibraryHolder(), com.butterflyatlas.app.testing.UpdatePlaces.bundles(
                com.butterflyatlas.app.testing.UpdatePlaces.fixture(), v2, v3)).setup(other.toString()))
                .as("choosing the folder: that screen has no Try again").hasMessage("Place names were updated to 2026-11-01,"
                        + " but the update to 2027-02-01 couldn't be applied: something unexpected stopped it. If this keeps"
                        + " happening, contact the Butterfly Atlas developers with what you did just before. Nothing of that"
                        + " last update was kept.");
    }

    @Test
    void aLibraryOnPlaceDataThisCopyLacksButNotNewerSaysSo() {
        Path folder = rememberedOnFirstList();
        exec(folder, "INSERT INTO place_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-01', 'x', 'now')");
        SetupService setup = service(holder);

        setup.startup(Optional.empty());

        assertThat(setup.status().problem()).isEqualTo("This library uses place data (2026-10-01) that this copy of"
                + " Butterfly Atlas doesn't have. Open it with the copy of Butterfly Atlas it was last used with, or a newer"
                + " one.");
        assertThat(setup.status().retry()).as("Try again can't help").isFalse();
    }

    @Test
    void aMigrationThatFailsIsSaidInPlainWordsAndTheRawErrorStaysInTheLog() throws Exception {
        Path folder = rememberedOnFirstList();
        // As a version 9 library whose V10 cannot run: place_update is in the way.
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("atlas.db"));
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM flyway_schema_history WHERE version = '10'");
            st.executeUpdate("ALTER TABLE place DROP COLUMN replaced_by");
        }
        SetupService setup = service(holder);

        setup.startup(Optional.empty());

        assertThat(setup.status().problem()).isEqualTo("Butterfly Atlas could not open your library at " + folder
                + ". Something unexpected stopped it. If this keeps happening, contact the Butterfly Atlas developers with"
                + " what you did just before.");
        assertThat(setup.status().retry()).isTrue();
    }

    @Test
    void everyOpenFailureIsSaidInPlainWords() {
        Path folder = home;
        assertThat(SetupService.plain(folder, new com.butterflyatlas.app.db.DatabaseException("Database write failed",
                new org.sqlite.SQLiteException("[SQLITE_BUSY] The database file is locked (database is locked)",
                        org.sqlite.SQLiteErrorCode.SQLITE_BUSY)), path -> Long.MAX_VALUE))
                .isEqualTo("Close any other program using your library folder (a sync tool, another copy of Butterfly"
                        + " Atlas), then try again.");
        assertThat(SetupService.plain(folder, new java.io.UncheckedIOException(new java.io.IOException("Input/output error")),
                path -> Long.MAX_VALUE)).isEqualTo("Your library couldn't be read or written. Check the drive it is on,"
                + " then try again.");
        assertThat(SetupService.plain(folder, new RuntimeException("x"), path -> 0L))
                .isEqualTo("The drive with your library is full. Free some space, then try again.");
        assertThat(SetupService.plain(home.resolve("gone"), new RuntimeException("x"), path -> Long.MAX_VALUE))
                .isEqualTo("The drive with your library can't be found. Reconnect it, then try again.");
        for (org.sqlite.SQLiteErrorCode code : List.of(org.sqlite.SQLiteErrorCode.SQLITE_READONLY,
                org.sqlite.SQLiteErrorCode.SQLITE_CANTOPEN, org.sqlite.SQLiteErrorCode.SQLITE_PERM)) {
            assertThat(SetupService.plain(folder, new com.butterflyatlas.app.db.DatabaseException("Could not prepare",
                    new org.sqlite.SQLiteException("[" + code.name() + "] x", code)), path -> Long.MAX_VALUE)).as(code.name())
                    .isEqualTo("Butterfly Atlas can't write to your library folder. Check that the drive isn't read-only"
                            + " and that the folder may be changed, then try again.");
        }
        assertThat(SetupService.plain(folder, new IllegalStateException("NPE in a corner"), path -> Long.MAX_VALUE))
                .isEqualTo("Something unexpected stopped it. If this keeps happening, contact the Butterfly Atlas developers"
                        + " with what you did just before.");
    }

    @Test
    void everyReasonReadsRightAfterTheProblemsColon() {
        assertThat(List.of(SetupService.FULL, SetupService.GONE, SetupService.LOCKED, SetupService.READ_ONLY,
                SetupService.UNREADABLE, SetupService.UNEXPECTED).stream().map(SetupService::lower).toList()).containsExactly(
                "the drive with your library is full. Free some space, then try again.",
                "the drive with your library can't be found. Reconnect it, then try again.",
                "close any other program using your library folder (a sync tool, another copy of Butterfly Atlas), then try"
                        + " again.",
                "Butterfly Atlas can't write to your library folder. Check that the drive isn't read-only and that the folder"
                        + " may be changed, then try again.",
                "your library couldn't be read or written. Check the drive it is on, then try again.",
                "something unexpected stopped it. If this keeps happening, contact the Butterfly Atlas developers with what"
                        + " you did just before.");
    }
}
