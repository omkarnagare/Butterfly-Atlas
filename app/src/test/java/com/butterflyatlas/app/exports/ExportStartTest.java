package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Check and start (4b contract §2): the refusals in order, the space line, the folder, the CSV and the records. */
class ExportStartTest {

    private static final long MB = 1024 * 1024;

    @TempDir
    Path home;

    private ExportFixture f;
    private long tiger1;
    private long tiger2;
    private long mormon;

    @BeforeEach
    void open() {
        f = new ExportFixture(home);
        tiger1 = f.photo("A5", "Danaus_chrysippus_01.jpg", TestImages.jpeg(16, 12, 1), "P4");
        tiger2 = f.photo("A5", "Danaus_chrysippus_02.jpg", TestImages.jpeg(16, 12, 2), null);
        mormon = f.photo("A12", "Papilio_polytes_01.jpg", TestImages.jpeg(16, 12, 3), "P8");
        f.exports.holdForStartup(); // the start alone: nothing is copied in these tests
    }

    @AfterEach
    void close() {
        f.exports.close();
    }

    private long bytes() throws IOException {
        return Files.size(f.file(tiger1)) + Files.size(f.file(tiger2)) + Files.size(f.file(mormon));
    }

    private Exports.Check check(String filter, String what, String path) {
        return f.exports.check(filter, what, path);
    }

    private static void refused(Exports.Check check, String code, String message) {
        assertThat(check.problem()).isEqualTo(new Exports.Refusal(code, message));
    }

    private static void refused(Runnable start, String code, String message) {
        assertThatThrownBy(start::run).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    private static List<String> files(Path folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    @Test
    void theCheckGivesTheSpaceLineAndTheNameThatWouldBeCreated() throws Exception {
        f.free.set(332_859_965_440L);

        Exports.Check check = check("", "photos", f.dest.toString());

        assertThat(check).isEqualTo(new Exports.Check(3, 2, bytes(), bytes() + 200 * MB, 332_859_965_440L,
                "Butterfly_Atlas_export_2026-10-09", null, new Exports.Raw(0, 0, 0), new Exports.Raw(0, 0, 0)));
        assertThat(check("", "csv", f.dest.toString())).isEqualTo(new Exports.Check(3, 6, bytes(), 0, 332_859_965_440L,
                "Butterfly_Atlas_species_list_2026-10-09.csv", null, new Exports.Raw(0, 0, 0), new Exports.Raw(0, 0, 0)));
        assertThat(check("", "both", f.dest.toString()).species()).isEqualTo(6);
        assertThat(f.dest.toFile().list()).as("a check writes nothing").isEmpty();
    }

    @Test
    void aTakenNameWithoutCaseGetsANumber() throws Exception {
        Files.createDirectory(f.dest.resolve("butterfly_atlas_export_2026-10-09"));
        Files.writeString(f.dest.resolve("Butterfly_Atlas_species_list_2026-10-09.csv.part"), "left over");

        assertThat(check("", "photos", f.dest.toString()).name()).isEqualTo("Butterfly_Atlas_export_2026-10-09_2");
        assertThat(check("", "csv", f.dest.toString()).name()).isEqualTo("Butterfly_Atlas_species_list_2026-10-09_2.csv");
    }

    /** The samples' photos are a few KB: the margin is the 200 MB floor. */
    private static final String NO_SPACE = "There isn't enough space: 2 KB needed plus 200.0 MB to spare, 100.0 MB free.";

    @Test
    void theRefusalsComeInTheContractsOrder() throws Exception {
        Path file = Files.writeString(home.resolve("note.txt"), "x");

        refused(check("", "photos", null), "invalid", "Choose where to export.");
        refused(check("", "photos", "  "), "invalid", "Choose where to export.");
        refused(check("", "photos", "relative/folder"), "invalid", "Choose where to export.");
        refused(check("", "photos", home.resolve("gone").toString()), "not_found", "That folder can't be found.");
        refused(check("", "photos", file.toString()), "not_folder", "That isn't a folder.");
        refused(check("", "photos", f.library.folder().toString()), "library_folder", "Choose a folder outside the library.");
        refused(check("", "photos", f.library.folder().resolve("images").toString()), "library_folder",
                "Choose a folder outside the library.");
        refused(check("taxa=A8", "photos", f.dest.toString()), "nothing", "No photos match these filters.");
        refused(check("status=not&taxa=A5", "csv", f.dest.toString()), "nothing", "No species match these filters.");
        assertThat(check("taxa=A8", "csv", f.dest.toString()).problem()).as("a species with no photo is still a row").isNull();
        f.free.set(100 * MB);
        refused(check("", "both", f.dest.toString()), "no_space", NO_SPACE);
        assertThat(check("", "both", f.dest.toString()).freeBytes()).isEqualTo(100 * MB);
        assertThat(check("", "csv", f.dest.toString()).problem()).as("the list needs no space line").isNull();
    }

    @Test
    void whenTwoConditionsBreakTheFirstInTheContractsOrderIsGiven() throws Exception {
        refused(check("taxa=A8", "photos", f.library.folder().toString()), "library_folder",
                "Choose a folder outside the library.");
        f.free.set(100 * MB);
        refused(check("taxa=A8", "photos", f.dest.toString()), "nothing", "No photos match these filters.");
        Path folder = home;
        while (folder.toString().length() < 190) {
            folder = folder.resolve("long_folder_name_x");
        }
        Path deep = Files.createDirectories(folder);
        refused(() -> f.exports.start(new Exports.Request("", "photos", deep.toString(), "species", true, "none")),
                "no_space", NO_SPACE);
    }

    @Test
    void sizesReadAsTheUiWritesThem() {
        assertThat(ExportErrors.size(0)).isEqualTo("0 B");
        assertThat(ExportErrors.size(980)).isEqualTo("980 B");
        assertThat(ExportErrors.size(2048)).isEqualTo("2 KB");
        assertThat(ExportErrors.size(1_048_575)).isEqualTo("1.0 MB");
        assertThat(ExportErrors.size(1_468_006)).isEqualTo("1.4 MB");
        assertThat(ExportErrors.size(4_719_430_860L)).isEqualTo("4.4 GB");
        assertThat(ExportErrors.size(1_181_116_006L)).isEqualTo("1.1 GB");
        assertThat(ExportErrors.noSpace(456_340_275L, 200L * MB, 611_529_523L))
                .isEqualTo("There isn't enough space: 435.2 MB needed plus 200.0 MB to spare, 583.2 MB free.");
        assertThat(ExportErrors.noSpace(4_719_430_860L, 47_194_308L, 1_181_116_006L))
                .isEqualTo("There isn't enough space: 4.4 GB needed plus 45.0 MB to spare, 1.1 GB free.");
        assertThat(ExportErrors.driveFull(2_254_857_830L)).isEqualTo("The drive is full: about 2.1 GB more is needed.");
    }

    @Test
    void aFolderThatCannotBeWrittenIsRefused() throws Exception {
        Path locked = Files.createDirectory(home.resolve("Locked"));
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions here");
        }
        try {
            assumeTrue(!Files.isWritable(locked), "running as a user who can write anywhere");
            refused(check("", "photos", locked.toString()), "not_writable", "Butterfly Atlas can't write to that folder.");
            refused(check("taxa=A8", "photos", locked.toString()), "not_writable",
                    "Butterfly Atlas can't write to that folder.");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aPathTooLongForTheFoldersIsRefusedAndOneFolderMayStillFit() throws Exception {
        Path folder = home;
        while (folder.toString().length() < 190) {
            folder = folder.resolve("long_folder_name_x");
        }
        Path deep = Files.createDirectories(folder);

        assertThat(check("", "photos", deep.toString()).problem()).as("the check reads no photo list: no path lengths")
                .isNull();
        refused(() -> f.exports.start(new Exports.Request("", "photos", deep.toString(), "species", true, "none")),
                "path_too_long", "The chosen folder's path is too long for these folders. Choose a folder nearer the top of"
                        + " the drive, or All photos in one folder.");
        assertThat(deep.toFile().list()).as("nothing is left behind").isEmpty();
        Exports.Export flat = f.exports.start(new Exports.Request("", "photos", deep.toString(), "flat", true, "none"));
        assertThat(flat.state()).isEqualTo("running");
    }

    @Test
    void aSpeciesListOnItsOwnWhosePathWouldBeTooLongIsRefusedAtTheStart() throws Exception {
        Path folder = home;
        while (folder.toString().length() + 1 + "Butterfly_Atlas_species_list_2026-10-09.csv.part".length() <= 259) {
            folder = folder.resolve("x");
        }
        Path deep = Files.createDirectories(folder);
        Path fits = deep.getParent();

        assertThat(check("", "csv", deep.toString()).problem()).isNull();
        refused(() -> f.exports.start(new Exports.Request("", "csv", deep.toString(), null, null, null)), "path_too_long",
               
                "The chosen folder's path is too long for the species list. Choose a folder nearer the top of the drive.");
        assertThat(deep.toFile().list()).isEmpty();
        assertThat(f.exports.start(new Exports.Request("", "csv", fits.toString(), null, null, null)).state())
                .isEqualTo("done");
    }

    @Test
    void anExportFolderTooDeepForItsSpeciesListIsRefusedAtTheStart() throws Exception {
        int name = 238 - "Butterfly_Atlas_export_2026-10-09".length() - 1 - home.toString().length() - 1;
        Path deep = Files.createDirectories(home.resolve("y".repeat(name)));
        Path fits = Files.createDirectories(home.resolve("z".repeat(name - 1)));
        assertThat(deep.resolve("Butterfly_Atlas_export_2026-10-09").toString()).hasSize(238);

        refused(() -> f.exports.start(new Exports.Request("", "both", deep.toString(), "flat", true, null)), "path_too_long",
               
                "The chosen folder's path is too long. Choose a folder nearer the top of the drive.");
        assertThat(deep.toFile().list()).as("nothing is left behind").isEmpty();
        assertThat(f.exports.start(new Exports.Request("", "both", fits.toString(), "flat", true, null)).state())
                .as("237: species-list.csv.part fits in 259").isEqualTo("running");
    }

    @Test
    void aDestinationThatVanishesBeforeTheFolderIsMadeIsNotFound() {
        f.exports.beforeFolder(p -> p.getParent().toFile().delete());

        refused(() -> f.exports.start(f.request("", "photos")), "not_found", "That folder can't be found.");
    }

    @Test
    void aSpeciesListThatFailsWhileWrittenLeavesNothing() {
        f.exports.csvWriter((out, rows, depth, missing) -> {
            out.write(1);
            throw new IllegalStateException("a bug");
        });

        assertThatThrownBy(() -> f.exports.start(f.request("", "both"))).hasMessage("a bug");
        assertThatThrownBy(() -> f.exports.start(f.request("", "csv"))).hasMessage("a bug");

        assertThat(f.dest.toFile().list()).isEmpty();
        assertThat(f.exports.store().current()).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // Windows places the list with a move, never a hard link: the test below covers it there
    void aSpeciesListNeverReplacesAFileMadeAtItsNameMeanwhile() throws Exception {
        Path taken = f.dest.resolve("Butterfly_Atlas_species_list_2026-10-09.csv");
        f.exports.linker((target, existing) -> {
            if (target.equals(taken)) {
                Files.writeString(taken, "made in the gap");
            }
            Files.createLink(target, existing);
        });

        Exports.Export export = f.exports.start(f.request("", "csv"));

        assertThat(export.csv()).isEqualTo("Butterfly_Atlas_species_list_2026-10-09_2.csv");
        assertThat(taken).hasContent("made in the gap");
        assertThat(files(f.dest)).containsExactly("Butterfly_Atlas_species_list_2026-10-09.csv",
                "Butterfly_Atlas_species_list_2026-10-09_2.csv");
    }

    @Test
    void aSpeciesListPlacedByAMoveNeverReplacesAFileMadeAtItsNameJustBefore() throws Exception {
        Path taken = f.dest.resolve("Butterfly_Atlas_species_list_2026-10-09.csv");
        f.exports.linker((target, existing) -> {
            throw new FileSystemException(target.toString(), null, "Operation not supported");
        });
        f.exports.beforeMove(target -> {
            if (target.equals(taken)) {
                try {
                    Files.writeString(taken, "made in the gap");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });

        Exports.Export export = f.exports.start(f.request("", "csv"));

        assertThat(export.csv()).isEqualTo("Butterfly_Atlas_species_list_2026-10-09_2.csv");
        assertThat(taken).hasContent("made in the gap");
        assertThat(files(f.dest)).containsExactly("Butterfly_Atlas_species_list_2026-10-09.csv",
                "Butterfly_Atlas_species_list_2026-10-09_2.csv");
    }

    @Test
    void aFailureClosingTheConnectionAfterTheCommitNeverRemovesTheRecordedExportsFiles() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        TestDatabases.failCloseAfterCommit(f.db, armed);
        f.exports.beforeRecord(() -> armed.set(true));

        assertThatThrownBy(() -> f.exports.start(f.request("", "both")))
                .isInstanceOf(DatabaseException.class);

        Path folder = f.dest.resolve("Butterfly_Atlas_export_2026-10-09");
        assertThat(files(folder)).containsExactly("species-list.csv");
        assertThat(f.exports.store().current()).isPresent();
    }

    @Test
    void anUntaggedPhotoWithNoPlaceHasItsRowInTheList() throws Exception {
        f.photo("A8", "Tirumala_limniace_01.jpg", TestImages.jpeg(8, 6, 7), null);

        Exports.Export export = f.exports.start(new Exports.Request("taxa=A8", "csv", f.dest.toString(), null, null,
                "district"));

        assertThat(Files.readString(f.dest.resolve(export.csv()), StandardCharsets.UTF_8)).endsWith(",,,,1,,Official,A8\r\n");
    }

    @Test
    void badChoicesAreRefused() {
        refused(() -> f.exports.check("", "pdf", f.dest.toString()), "invalid", "Unknown export “pdf”.");
        refused(() -> f.exports.check("", null, f.dest.toString()), "invalid", "Unknown export “”.");
        refused(() -> f.exports.check("status=maybe", "photos", f.dest.toString()), "invalid", "Unknown status “maybe”.");
        refused(() -> f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "tree", null, null)), "invalid",
                "Unknown layout “tree”.");
        refused(() -> f.exports.start(new Exports.Request("", "both", f.dest.toString(), null, null, "town")), "invalid",
                "Unknown place depth “town”.");
    }

    @Test
    void aPhotoExportCreatesItsFolderAndRecordsEveryPhotoWithItsTarget() throws Exception {
        Exports.Export export = f.exports.start(f.request("", "photos"));

        Path folder = f.dest.resolve("Butterfly_Atlas_export_2026-10-09");
        assertThat(folder).isDirectory();
        assertThat(files(folder)).as("nothing copied yet").isEmpty();
        assertThat(export).isEqualTo(new Exports.Export(export.id(), "running", "photos", "species", true, "none", "",
                folder.toString(), null, 2, 3, 0, 0, 0, 0, 0, bytes(), 0, null, null, null, 0, "2026-10-09T08:00:00Z", null, 0, 0));
        assertThat(f.exports.store().targets(export.id())).containsExactly(
                "Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus/Danaus_chrysippus_01.jpg",
                "Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus/Danaus_chrysippus_02.jpg",
                "Papilionidae/Papilioninae/Papilio/Papilio_polytes/Papilio_polytes_01.jpg");
        assertThat(f.exports.store().pending(export.id(), 0, 10)).extracting(ExportStore.Item::imageId)
                .containsExactly(tiger1, tiger2, mormon);
    }

    @Test
    void aCustomSpeciesGoesUnderItsFamilysCustomAdditionsAndIsCustomInTheList() throws Exception {
        TestBundles.addManualSpecies(f.db, "U1", "Neptis sankara", "A1");
        f.photo("U1", "Neptis_sankara_01.jpg", TestImages.jpeg(16, 12, 4), null);

        Exports.Export export = f.exports.start(f.request("taxa=U:butterflies", "both"));

        assertThat(f.exports.store().targets(export.id()))
                .containsExactly("Nymphalidae/Custom_additions/Neptis_sankara/Neptis_sankara_01.jpg");
        String csv = Files.readString(f.dest.resolve("Butterfly_Atlas_export_2026-10-09").resolve("species-list.csv"),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("\r\nNymphalidae,,,Neptis,Neptis sankara,,1,,Custom,U1\r\n");
    }

    @Test
    void oneExportAtATime() {
        f.exports.start(f.request("", "photos"));

        refused(() -> f.exports.start(f.request("", "photos")), "export_running", "Finish or cancel the export in progress first.");
        refused(() -> f.exports.start(f.request("", "csv")), "export_running", "Finish or cancel the export in progress first.");
    }

    @Test
    void bothWritesTheListIntoTheExportFolderFromTheSameFilter() throws Exception {
        Exports.Export export = f.exports.start(new Exports.Request("places=P1&taxa=A4,A404", "both", f.dest.toString(), "flat",
                false, "district"));

        Path folder = f.dest.resolve("Butterfly_Atlas_export_2026-10-09");
        assertThat(export.csv()).isEqualTo("species-list.csv");
        assertThat(export.filter()).isEqualTo("taxa=A4&places=P1");
        assertThat(export.layout()).isEqualTo("flat");
        assertThat(export.stripLocation()).isFalse();
        assertThat(export.total()).isEqualTo(1);
        assertThat(export.species()).isEqualTo(2);
        assertThat(files(folder)).containsExactly("species-list.csv");
        String csv = Files.readString(folder.resolve("species-list.csv"), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFFamily,").contains(",Country,State,District,")
                .contains("Danaus chrysippus,Plain Tiger,Testland,Northstate,Alpha,1,,Official,A5\r\n")
                .contains("Danaus genutia,Striped Tiger,,,,0,,Official,A6\r\n");
        assertThat(f.exports.store().targets(export.id())).containsExactly("Danaus_chrysippus_01.jpg");
    }

    @Test
    void aSpeciesListOnItsOwnIsDoneInTheAnswerAndASecondOneGetsANumber() throws Exception {
        Exports.Export first = f.exports.start(new Exports.Request("stages=missing", "csv", f.dest.toString(), null, null,
                "country"));
        Exports.Export second = f.exports.start(f.request("", "csv"));

        assertThat(first.state()).isEqualTo("done");
        assertThat(first.path()).isEqualTo(f.dest.resolve("Butterfly_Atlas_species_list_2026-10-09.csv").toString());
        assertThat(first.csv()).isEqualTo("Butterfly_Atlas_species_list_2026-10-09.csv");
        assertThat(first.finishedAt()).isEqualTo("2026-10-09T08:00:00Z");
        assertThat(first.total()).isZero();
        assertThat(second.csv()).isEqualTo("Butterfly_Atlas_species_list_2026-10-09_2.csv");
        assertThat(files(f.dest)).containsExactly("Butterfly_Atlas_species_list_2026-10-09.csv",
                "Butterfly_Atlas_species_list_2026-10-09_2.csv");
        assertThat(Files.readString(f.dest.resolve(first.csv()))).contains(",Country,Photo count,Tags present,Missing,Source,")
                .contains("Plain Tiger,Testland,1,,\"egg, larva, pupa\",Official,A5");
        assertThat(f.exports.store().past()).hasSize(2);
    }

    @Test
    void aFolderMadeMeanwhileIsNeverUsedTheNextNumberIs() throws Exception {
        f.exports.beforeFolder(p -> {
            try {
                Files.createDirectory(p.resolveSibling("Butterfly_Atlas_export_2026-10-09"));
            } catch (IOException e) {
                // the second call: already there
            }
        });

        Exports.Export export = f.exports.start(f.request("", "photos"));

        assertThat(export.path()).endsWith("Butterfly_Atlas_export_2026-10-09_2");
        assertThat(f.dest.resolve("Butterfly_Atlas_export_2026-10-09").toFile().list()).isEmpty();
    }

    @Test
    void onceTheRunIsRecordedItsFolderAndListAreNeverRemovedByTheStart() throws Exception {
        f.exports.afterRecord(() -> {
            throw new IllegalStateException("after the commit");
        });

        assertThatThrownBy(() -> f.exports.start(f.request("", "both"))).hasMessage("after the commit");

        Path folder = f.dest.resolve("Butterfly_Atlas_export_2026-10-09");
        assertThat(files(folder)).containsExactly("species-list.csv");
        assertThat(f.exports.store().current()).isPresent();
    }

    @Test
    void inOneFolderAPhotoNamedLikeTheListGetsANumber() throws Exception {
        long odd = f.photo("A8", "Species-List.CSV", TestImages.jpeg(8, 6, 9), null);

        Exports.Export export = f.exports.start(new Exports.Request("taxa=A8", "both", f.dest.toString(), "flat", true, null));

        assertThat(f.exports.store().targets(export.id())).containsExactly("Species-List_2.CSV");
        assertThat(f.exports.store().pending(export.id(), 0, 9)).extracting(ExportStore.Item::imageId).containsExactly(odd);
    }

    @Test
    void whenTheRecordCannotBeWrittenTheFolderAndTheListGoAgain() {
        f.exports.beforeRecord(() -> {
            throw new IllegalStateException("disk I/O error");
        });

        assertThatThrownBy(() -> f.exports.start(f.request("", "both"))).hasMessage("disk I/O error");
        assertThatThrownBy(() -> f.exports.start(f.request("", "csv"))).hasMessage("disk I/O error");

        assertThat(f.dest.toFile().list()).isEmpty();
        assertThat(f.exports.store().current()).isEmpty();
        assertThat(f.exports.store().past()).isEmpty();
    }
}
