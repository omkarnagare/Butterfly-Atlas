package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.window.InstanceLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opening a library takes its lock, refuses one in use or too new, and says when the drive cannot lock (audit 5, 10, 20). */
class SetupLockTest {

    @TempDir
    Path home;

    private final List<Path> locked = new ArrayList<>();
    private final List<String> released = new ArrayList<>();

    private ConfigStore config() {
        return new ConfigStore(home.resolve("config"));
    }

    private SetupService service(Function<Path, InstanceLock.Result> locks) {
        return new SetupService(config(), new LibraryHolder(), TestBundles.fixture(), TestPlaces.bundles(), home,
                TestBundles.CLOCK, locks);
    }

    private SetupService real() {
        return service(InstanceLock::acquire);
    }

    @Test
    void aLibraryInUseIsRefusedByTheSetupWithItsOwnCode() {
        SetupService first = real();
        first.setup(home.resolve("Atlas").toString());

        SetupService second = real();
        assertThatThrownBy(() -> second.setup(home.resolve("Atlas").toString())).isInstanceOfSatisfying(AtlasException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("library_in_use");
                    assertThat(e.getMessage()).isEqualTo("Another copy of Butterfly Atlas is using this library.");
                });

        assertThat(second.status().configured()).isFalse();
    }

    @Test
    void aRememberedLibraryInUseIsTheProblemOnTheSetupScreenAndTryAgainWorksOnceItIsFree() throws Exception {
        Path folder = home.resolve("Atlas");
        InstanceLock.Result other = InstanceLock.acquire(Files.createDirectories(folder));
        config().save(new AppConfig(folder.toString()));
        SetupService service = real();

        service.startup(Optional.empty());

        SetupService.Status status = service.status();
        assertThat(status.configured()).isFalse();
        assertThat(status.problem()).isEqualTo("Another copy of Butterfly Atlas is using this library.");
        assertThat(status.retry()).isTrue();

        other.lock().close();
        assertThat(service.retry().configured()).isTrue();
    }

    @Test
    void aLibraryNamedByDataDirThatIsInUseStopsTheStart() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        InstanceLock.Result other = InstanceLock.acquire(folder);
        SetupService service = real();

        assertThatThrownBy(() -> service.startup(Optional.of(folder))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("library_in_use"));

        other.lock().close();
    }

    @Test
    void aDriveThatCannotLockStartsAnywayWithAStandingWarning() {
        SetupService service = service(folder -> new InstanceLock.Result(InstanceLock.State.CANNOT_LOCK, null, "No locks"));

        assertThat(service.lockWarning()).isFalse();
        service.setup(home.resolve("Atlas").toString());

        assertThat(service.status().configured()).isTrue();
        assertThat(service.lockWarning()).isTrue();
    }

    @Test
    void aLibraryThatFailsToOpenGivesItsLockBack() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        Files.writeString(folder.resolve(Database.FILE_NAME), "this is not a database, just text in the way ".repeat(50));
        SetupService service = real();

        assertThatThrownBy(() -> service.setup(folder.toString())).isInstanceOf(AtlasException.class);

        InstanceLock.Result again = InstanceLock.acquire(folder);
        assertThat(again.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        again.lock().close();
    }

    private void newerLibrary(Path folder) {
        Database db = Database.open(folder.resolve(Database.FILE_NAME));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO flyway_schema_history (installed_rank, version,"
                    + " description, type, script, checksum, installed_by, execution_time, success) VALUES"
                    + " ((SELECT MAX(installed_rank) + 1 FROM flyway_schema_history), '99', 'newer', 'SQL', 'V99__x.sql', 0,"
                    + " 'x', 0, 1)")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void aLibraryFromANewerVersionIs409LibraryTooNewAtSetup() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        newerLibrary(folder);

        assertThatThrownBy(() -> real().setup(folder.toString())).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("library_too_new");
            assertThat(e.getMessage()).startsWith("This library was made by a newer Butterfly Atlas (database version 99;")
                    .endsWith("Install the newer version.");
        });
        InstanceLock.Result free = InstanceLock.acquire(folder);
        assertThat(free.state()).as("its lock is given back").isEqualTo(InstanceLock.State.ACQUIRED);
        free.lock().close();
    }

    @Test
    void aRememberedLibraryFromANewerVersionIsThePlainProblemWithNoTryAgain() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        newerLibrary(folder);
        config().save(new AppConfig(folder.toString()));
        SetupService service = real();

        service.startup(Optional.empty());

        SetupService.Status status = service.status();
        assertThat(status.problem()).startsWith("This library was made by a newer Butterfly Atlas");
        assertThat(status.retry()).isFalse();
    }

    @Test
    void withoutLocksNothingChanges() {
        SetupService service = new SetupService(config(), new LibraryHolder(), TestBundles.fixture(), TestPlaces.bundles(),
                home, TestBundles.CLOCK);

        service.setup(home.resolve("Atlas").toString());
        new SetupService(config(), new LibraryHolder(), TestBundles.fixture(), TestPlaces.bundles(), home, TestBundles.CLOCK)
                .setup(home.resolve("Atlas").toString());

        assertThat(service.lockWarning()).isFalse();
    }
}
