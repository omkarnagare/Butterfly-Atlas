package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.window.InstanceLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is checked before a change starts and while the folder browser looks (change-library-folder spec §3, §4, contract
 * §3): the refusals, the free space a move needs, running work, a library another copy holds, and a fixed folder.
 */
class LibraryChangeChecksTest {

    private static final long MB = 1_000_000L;

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private SetupService setup;
    private Path current;
    private long free = 500_000 * MB;
    private final AtomicReference<String> running = new AtomicReference<>();

    @BeforeEach
    void open() throws Exception {
        setup = new SetupService(new ConfigStore(home.resolve("config")), holder, TestBundles.fixture(), TestPlaces.bundles(),
                home, TestBundles.CLOCK);
        current = home.resolve("Current");
        setup.setup(current.toString());
        Files.createDirectories(current.resolve("images"));
        Files.write(current.resolve("images").resolve("p.jpg"), new byte[4_000_000]);
    }

    private LibraryChange change() {
        return new LibraryChange(holder::library, setup, ChangeSteps.REAL, folder -> free, LibraryChange.Background.NONE,
                (kind, from, to) -> { }, () -> Optional.ofNullable(running.get()));
    }

    private static Path otherLibrary(Path folder) {
        Library.open(folder, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
        return folder;
    }

    private static Consumer<AtlasException> refused(AtlasException.Kind kind, String code, String message) {
        return e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo(message);
        };
    }

    @Test
    void theOverviewNamesTheFolderItsSizeAndTheFreeSpace() throws Exception {
        LibraryChange.Overview overview = change().overview();

        assertThat(overview.folder()).isEqualTo(current.toString());
        assertThat(overview.size()).isEqualTo(LibraryChange.size(current));
        assertThat(overview.size()).isGreaterThan(4_000_000);
        assertThat(overview.free()).isEqualTo(free);
        assertThat(overview.fixed()).isFalse();
        assertThat(overview.changing()).isNull();
    }

    @Test
    void aMoveGoesIntoAButterflyAtlasFolderOfTheFolderShownWithTheSpaceItNeeds() throws Exception {
        Path drive = Files.createDirectories(home.resolve("Drive"));

        LibraryChange.Inspect inspect = change().inspect(drive.toString(), LibraryChange.MOVE);

        long size = LibraryChange.size(current);
        assertThat(inspect.libraryFolder()).isEqualTo(drive.resolve("Butterfly_Atlas").toString());
        assertThat(inspect.kind()).isEqualTo(SetupService.CREATE);
        assertThat(inspect.refusal()).isNull();
        assertThat(inspect.free()).isEqualTo(free);
        assertThat(inspect.needed()).isEqualTo(Math.round(size * 1.05) + 200 * MB);
    }

    @Test
    void aMoveIsRefusedIntoALibraryTheLibraryItselfOrAFolderInsideIt() throws Exception {
        Path other = otherLibrary(home.resolve("Other"));

        assertThat(change().inspect(other.toString(), LibraryChange.MOVE).refusal()).isEqualTo(LibraryChange.EXISTING_LIBRARY);
        assertThat(change().inspect(current.toString(), LibraryChange.MOVE).refusal()).isEqualTo(LibraryChange.CURRENT_LIBRARY);
        assertThat(change().inspect(current.resolve("images").toString(), LibraryChange.MOVE).refusal())
                .isEqualTo(LibraryChange.INSIDE_LIBRARY);

        assertThatThrownBy(() -> change().move(other.toString())).isInstanceOfSatisfying(AtlasException.class,
                refused(AtlasException.Kind.INVALID, ErrorCodes.INVALID, LibraryChange.EXISTING_WORDS));
        assertThatThrownBy(() -> change().move(current.toString())).isInstanceOfSatisfying(AtlasException.class,
                refused(AtlasException.Kind.INVALID, ErrorCodes.INVALID, LibraryChange.OUTSIDE_WORDS));
        assertThatThrownBy(() -> change().move(current.resolve("images").toString()))
                .isInstanceOfSatisfying(AtlasException.class,
                        refused(AtlasException.Kind.INVALID, ErrorCodes.INVALID, LibraryChange.OUTSIDE_WORDS));
    }

    @Test
    void aMoveWithTooLittleSpaceIsRefusedSayingWhatItNeeds() throws Exception {
        Path drive = Files.createDirectories(home.resolve("Drive"));
        free = 100 * MB;

        LibraryChange.Inspect inspect = change().inspect(drive.toString(), LibraryChange.MOVE);
        assertThat(inspect.refusal()).isEqualTo(ErrorCodes.NOT_ENOUGH_SPACE);

        assertThatThrownBy(() -> change().move(drive.toString())).isInstanceOfSatisfying(AtlasException.class,
                refused(AtlasException.Kind.CONFLICT, ErrorCodes.NOT_ENOUGH_SPACE, "That drive has 100.0 MB free; moving"
                        + " your library needs about " + LibraryChange.words(inspect.needed()) + "."));
        assertThat(drive.resolve("Butterfly_Atlas")).doesNotExist();
    }

    @Test
    void aFolderTheSetupScreenWouldRefuseIsRefusedInItsWords() throws Exception {
        Path taken = Files.createDirectories(home.resolve("Taken").resolve("Butterfly_Atlas"));
        Files.writeString(taken.resolve("notes.txt"), "mine");

        assertThat(change().inspect(taken.getParent().toString(), LibraryChange.MOVE).kind()).isEqualTo(SetupService.TAKEN);
        assertThatThrownBy(() -> change().move(taken.getParent().toString())).isInstanceOfSatisfying(AtlasException.class,
                refused(AtlasException.Kind.INVALID, ErrorCodes.INVALID,
                        taken + " already exists and isn't a Butterfly Atlas library. Choose another folder."));
        assertThatThrownBy(() -> change().move("relative/folder")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.INVALID));
        assertThatThrownBy(() -> change().move(home.resolve("Missing").toString()))
                .isInstanceOfSatisfying(AtlasException.class,
                        refused(AtlasException.Kind.INVALID, ErrorCodes.INVALID,
                                "That folder can't be found. Choose another folder."));
    }

    @Test
    void runningWorkRefusesTheChangeNamingWhatRuns() throws Exception {
        Path drive = Files.createDirectories(home.resolve("Drive"));
        running.set("An export is running. Pause it or wait for it to finish, then try again.");

        assertThatThrownBy(() -> change().move(drive.toString())).isInstanceOfSatisfying(AtlasException.class,
                refused(AtlasException.Kind.CONFLICT, ErrorCodes.WORK_RUNNING,
                        "An export is running. Pause it or wait for it to finish, then try again."));
        assertThat(drive.resolve("Butterfly_Atlas")).doesNotExist();
    }

    @Test
    void aSwitchOpensAnotherLibraryButNotThisOneNorOneAnotherCopyHolds() throws Exception {
        Path other = otherLibrary(home.resolve("Other"));
        LibraryChange change = change();

        LibraryChange.Inspect fine = change.inspect(other.toString(), LibraryChange.SWITCH);
        assertThat(fine.kind()).isEqualTo(SetupService.EXISTING);
        assertThat(fine.refusal()).isNull();
        assertThat(fine.unfinished()).isEqualTo(new UnfinishedWork.Counts(0, 0, 0));
        assertThat(fine.free()).as("move only").isNull();
        assertThat(fine.needed()).isNull();
        assertThat(change.inspect(home.resolve("Empty").toString(), LibraryChange.SWITCH).refusal()).isNull();
        assertThat(change.inspect(current.toString(), LibraryChange.SWITCH).refusal()).isEqualTo(LibraryChange.CURRENT_LIBRARY);

        InstanceLock.Result held = InstanceLock.acquire(other);
        try {
            assertThat(change.inspect(other.toString(), LibraryChange.SWITCH).refusal()).isEqualTo(ErrorCodes.LIBRARY_IN_USE);
        } finally {
            held.lock().close();
        }
        assertThat(InstanceLock.acquire(other).state()).as("the probe gave the lock back")
                .isEqualTo(InstanceLock.State.ACQUIRED);
    }

    @Test
    void aCopyStartedOnAFixedFolderRefusesEveryChange() throws Exception {
        LibraryHolder fixedHolder = new LibraryHolder();
        SetupService fixedSetup = new SetupService(new ConfigStore(home.resolve("config2")), fixedHolder,
                TestBundles.fixture(), TestPlaces.bundles(), home, TestBundles.CLOCK);
        fixedSetup.startup(Optional.of(home.resolve("Dev")));
        LibraryChange change = new LibraryChange(fixedHolder::library, fixedSetup, ChangeSteps.REAL, folder -> free,
                LibraryChange.Background.NONE, (kind, from, to) -> { }, Optional::empty);
        Path drive = Files.createDirectories(home.resolve("Drive"));

        assertThat(change.overview().fixed()).isTrue();
        assertThatThrownBy(() -> change.move(drive.toString())).isInstanceOfSatisfying(AtlasException.class,
                refused(AtlasException.Kind.CONFLICT, ErrorCodes.FIXED_FOLDER, LibraryChange.FIXED_WORDS));
    }

    @Test
    void sizesAreWordedAsTheDriveShowsThem() {
        assertThat(LibraryChange.words(12_400_000_000L)).isEqualTo("12.4 GB");
        assertThat(LibraryChange.words(204_200_000L)).isEqualTo("204.2 MB");
        assertThat(LibraryChange.words(1_000_000_000_000L)).isEqualTo("1,000.0 GB");
        assertThat(LibraryChange.words(0)).isEqualTo("0.0 MB");
    }
}
