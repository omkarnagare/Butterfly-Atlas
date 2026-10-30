package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first open after a change of library folder (change-library-folder spec §6, §8): it ends the change (and clears a
 * switched-to library's unfinished work), notes a move, and when the new library won't open offers the old one, once.
 */
class FirstOpenAfterChangeTest {

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();

    private ConfigStore config() {
        return new ConfigStore(home.resolve("config"));
    }

    private SetupService service() {
        return new SetupService(config(), holder, TestBundles.fixture(), TestPlaces.bundles(), home, TestBundles.CLOCK);
    }

    private static Path library(Path folder, boolean unfinished) {
        Database db = Library.open(folder, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK).database();
        if (unfinished) {
            UnfinishedWorkTest.leaveUnfinished(db);
        }
        return folder;
    }

    private static int count(Path folder, String sql) {
        return Database.open(folder.resolve("atlas.db")).read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    private static final String OPEN_IMPORTS = "SELECT COUNT(*) FROM import_run WHERE state = 'open'";
    private static final String PAUSED_EXPORTS = "SELECT COUNT(*) FROM export_run WHERE state = 'paused'";

    @Test
    void afterAMoveTheMovedLibraryOpensAsItWasTheChangeEndsAndTheMoveIsNotedUntilDismissed() {
        Path old = library(home.resolve("Old"), true);
        Path moved = library(home.resolve("Moved"), true);
        config().save(new AppConfig(moved.toString(), "dark", old.toString(), true, true));
        SetupService setup = service();

        setup.startup(Optional.empty());

        assertThat(setup.status().configured()).isTrue();
        assertThat(holder.library().folder()).isEqualTo(moved);
        assertThat(config().load()).contains(new AppConfig(moved.toString(), "dark", old.toString(), null, true));
        assertThat(count(moved, OPEN_IMPORTS)).as("a move keeps paused and open work").isEqualTo(1);
        assertThat(setup.libraryMoved()).isEqualTo(new SetupService.Moved(moved.toString()));
        assertThat(setup.status().previousFolder()).isNull();
        setup.dismissMovedNote();
        assertThat(setup.libraryMoved()).isNull();
    }

    @Test
    void afterASwitchItsUnfinishedWorkIsClearedBeforeTheLibraryIsHandedOver() {
        Path old = library(home.resolve("Old"), false);
        Path other = library(home.resolve("Other"), true);
        config().save(new AppConfig(other.toString(), null, old.toString(), true, null));
        int[] seenOpen = new int[1];
        holder.whenOpened(library -> seenOpen[0] = count(other, OPEN_IMPORTS));
        SetupService setup = service();

        setup.startup(Optional.empty());

        assertThat(holder.library().folder()).isEqualTo(other);
        assertThat(seenOpen[0]).as("cleared before anything sees the library").isZero();
        assertThat(count(other, PAUSED_EXPORTS)).isZero();
        assertThat(config().load()).contains(new AppConfig(other.toString()));
        assertThat(setup.libraryMoved()).isNull();
    }

    @Test
    void aClearThatFailsOpensNothingAndOffersTheOldLibraryInstead() {
        Path old = library(home.resolve("Old"), false);
        Path other = library(home.resolve("Other"), true);
        Database.open(other.resolve("atlas.db")).write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TRIGGER refuse BEFORE UPDATE ON export_run BEGIN SELECT RAISE(ABORT, 'refused'); END")) {
                ps.executeUpdate();
            }
            return null;
        });
        config().save(new AppConfig(other.toString(), null, old.toString(), true, null));
        SetupService setup = service();

        setup.startup(Optional.empty());

        SetupService.Status status = setup.status();
        assertThat(status.configured()).isFalse();
        assertThat(status.problem()).startsWith("Butterfly Atlas couldn't clear the work left unfinished in your library at "
                + other + ", so it didn't open it.");
        assertThat(status.previousFolder()).isEqualTo(old.toString());
        assertThat(status.movedCopy()).isNull();
        assertThat(count(other, OPEN_IMPORTS)).as("rolled back").isEqualTo(1);
    }

    @Test
    void aMovedLibraryThatWontOpenOffersTheOldOneWhichOpensAsItWasAndTheChangeIsUndone() {
        Path old = library(home.resolve("Old"), true);
        Path moved = home.resolve("Unplugged").resolve("Butterfly_Atlas");
        config().save(new AppConfig(moved.toString(), "light", old.toString(), true, true));
        SetupService setup = service();

        setup.startup(Optional.empty());

        SetupService.Status offered = setup.status();
        assertThat(offered.configured()).isFalse();
        assertThat(offered.problem()).startsWith("Butterfly Atlas could not find your library at " + moved);
        assertThat(offered.retry()).isTrue();
        assertThat(offered.previousFolder()).isEqualTo(old.toString());
        assertThat(offered.movedCopy()).isEqualTo(moved.toString());

        SetupService.Status back = setup.usePrevious();

        assertThat(back.configured()).isTrue();
        assertThat(back.dataFolder()).isEqualTo(old.toString());
        assertThat(back.previousFolder()).isNull();
        assertThat(holder.library().folder()).isEqualTo(old);
        assertThat(count(old, OPEN_IMPORTS)).as("as it was: its paused work kept").isEqualTo(1);
        assertThat(config().load()).contains(new AppConfig(old.toString(), "light"));
        assertThat(setup.libraryMoved()).isNull();
    }

    @Test
    void theOfferIsOnlyForTheFirstOpenAfterAChange() {
        Path old = library(home.resolve("Old"), false);
        Path moved = library(home.resolve("Moved"), false);
        config().save(new AppConfig(moved.toString(), null, old.toString(), true, true));
        service().startup(Optional.empty());

        config().save(new AppConfig(home.resolve("Gone").toString(), null, old.toString(), null, true));
        SetupService later = new SetupService(config(), new LibraryHolder(), TestBundles.fixture(), TestPlaces.bundles(),
                home, TestBundles.CLOCK);
        later.startup(Optional.empty());

        assertThat(later.status().configured()).isFalse();
        assertThat(later.status().previousFolder()).as("the user has worked in the new library since").isNull();
        assertThatThrownBy(later::usePrevious).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo(ErrorCodes.NO_PREVIOUS);
        });
    }

    @Test
    void tryAgainThatOpensTheNewLibraryEndsTheChangeAndTheOffer() throws Exception {
        Path old = library(home.resolve("Old"), false);
        Path other = home.resolve("Later");
        config().save(new AppConfig(other.toString(), null, old.toString(), true, null));
        SetupService setup = service();
        setup.startup(Optional.empty());
        assertThat(setup.status().previousFolder()).isEqualTo(old.toString());

        library(other, true);
        SetupService.Status opened = setup.retry();

        assertThat(opened.configured()).isTrue();
        assertThat(opened.previousFolder()).isNull();
        assertThat(count(other, OPEN_IMPORTS)).as("a switch's first open clears").isZero();
        assertThat(config().load()).contains(new AppConfig(other.toString()));
    }

    @Test
    void choosingAnotherFolderOnTheSetupScreenEndsTheOffer() {
        Path old = library(home.resolve("Old"), false);
        config().save(new AppConfig(home.resolve("Gone").toString(), null, old.toString(), true, true));
        SetupService setup = service();
        setup.startup(Optional.empty());

        setup.setup(home.resolve("Fresh").toString());

        assertThat(setup.status().previousFolder()).isNull();
        assertThat(config().load()).contains(new AppConfig(home.resolve("Fresh").toString()));
    }
}
