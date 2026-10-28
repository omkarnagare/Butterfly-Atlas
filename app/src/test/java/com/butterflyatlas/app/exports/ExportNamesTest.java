package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Names an export creates (4b contract §1): safe on every platform, no clashes, within Windows' path length. */
class ExportNamesTest {

    private static final String ROOT = "/Volumes/Backup/Butterfly_Atlas_export_2026-10-13";

    private static ExportNames.Source photo(long id, String fileName, String... folders) {
        return new ExportNames.Source(id, fileName, List.of(folders));
    }

    @Test
    void theExportFolderAndTheCsvAreNamedByDate() {
        LocalDate day = LocalDate.of(2026, 10, 13);

        assertThat(ExportNames.exportFolder(day)).isEqualTo("Butterfly_Atlas_export_2026-10-13");
        assertThat(ExportNames.csvFile(day)).isEqualTo("Butterfly_Atlas_species_list_2026-10-13.csv");
        assertThat(ExportNames.CSV_IN_FOLDER).isEqualTo("species-list.csv");
    }

    @Test
    void aTakenNameGetsTwoThreeAndSoOnComparedWithoutCase() {
        assertThat(ExportNames.numbered("Butterfly_Atlas_export_2026-10-13", 1)).isEqualTo("Butterfly_Atlas_export_2026-10-13");
        assertThat(ExportNames.numbered("Butterfly_Atlas_export_2026-10-13", 2)).isEqualTo("Butterfly_Atlas_export_2026-10-13_2");
        assertThat(ExportNames.numbered("Butterfly_Atlas_species_list_2026-10-13.csv", 3))
                .isEqualTo("Butterfly_Atlas_species_list_2026-10-13_3.csv");
        assertThat(ExportNames.firstFree("Butterfly_Atlas_export_2026-10-13",
                Set.of("butterfly_atlas_export_2026-10-13", "BUTTERFLY_ATLAS_EXPORT_2026-10-13_2", "Photos")))
                .isEqualTo("Butterfly_Atlas_export_2026-10-13_3");
        assertThat(ExportNames.firstFree("x.csv", Set.of())).isEqualTo("x.csv");
    }

    @Test
    void namesAreMadeSafeOnEveryPlatform() {
        assertThat(ExportNames.safe("Danaus chrysippus")).isEqualTo("Danaus_chrysippus");
        assertThat(ExportNames.safe("a<b>c:d\"e/f\\g|h?i*j")).isEqualTo("a_b_c_d_e_f_g_h_i_j");
        assertThat(ExportNames.safe("tab\there\u0001")).isEqualTo("tab_here_");
        assertThat(ExportNames.safe("photo. . .")).as("trailing dots and spaces dropped").isEqualTo("photo");
        assertThat(ExportNames.safe("a.b. c")).isEqualTo("a.b._c");
        assertThat(ExportNames.safe("no\u00A0break\u2003em\u3000wide")).as("Unicode spaces").isEqualTo("no_break_em_wide");
        assertThat(ExportNames.safe("photo\u00A0")).isEqualTo("photo");
        assertThat(ExportNames.safe("Gra\u0308fin")).as("NFC").isEqualTo("Gr\u00E4fin");
        assertThat(ExportNames.safe("photo...")).isEqualTo("photo");
        assertThat(ExportNames.safe("CON")).isEqualTo("_CON");
        assertThat(ExportNames.safe("nul.jpg")).isEqualTo("_nul.jpg");
        assertThat(ExportNames.safe("Com7.tar.gz")).isEqualTo("_Com7.tar.gz");
        assertThat(ExportNames.safe("LPT9")).isEqualTo("_LPT9");
        assertThat(ExportNames.safe("COM10.jpg")).isEqualTo("COM10.jpg");
        assertThat(ExportNames.safe("CONSOLE.jpg")).isEqualTo("CONSOLE.jpg");
        assertThat(ExportNames.safe("COM0.jpg")).isEqualTo("_COM0.jpg");
        assertThat(ExportNames.safe("lpt0")).isEqualTo("_lpt0");
        assertThat(ExportNames.safe("COM\u00B9.jpg")).as("superscript one").isEqualTo("_COM\u00B9.jpg");
        assertThat(ExportNames.safe("lpt\u00B2")).isEqualTo("_lpt\u00B2");
        assertThat(ExportNames.safe("Com\u00B3.txt")).isEqualTo("_Com\u00B3.txt");
        assertThat(ExportNames.safe("COM\u2074.jpg")).as("superscript four is no device").isEqualTo("COM\u2074.jpg");
        assertThat(ExportNames.safe("...")).isEqualTo("_");
        assertThat(ExportNames.safe("Gräfin Iphiclides")).isEqualTo("Gräfin_Iphiclides");
    }

    @Test
    void speciesFoldersLeaveOutAbsentRanksAndCustomSpeciesGoUnderTheirFamilysCustomAdditions() {
        assertThat(ExportNames.speciesFolders("Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus chrysippus", false))
                .containsExactly("Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus_chrysippus");
        assertThat(ExportNames.speciesFolders("Papilionidae", "Papilioninae", null, "Papilio", "Papilio polytes", false))
                .containsExactly("Papilionidae", "Papilioninae", "Papilio", "Papilio_polytes");
        assertThat(ExportNames.speciesFolders("Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus novus", true))
                .containsExactly("Nymphalidae", "Custom_additions", "Danaus_novus");
        assertThat(ExportNames.speciesFolders(null, null, null, "Danaus", "Danaus novus", true))
                .containsExactly("Custom_additions", "Danaus_novus");
    }

    @Test
    void anOfficialSpeciesWithNoFamilyGetsTheRanksItHasNotCustomAdditions() {
        assertThat(ExportNames.speciesFolders(null, null, null, "Danaus", "Danaus chrysippus", false))
                .containsExactly("Danaus", "Danaus_chrysippus");
        assertThat(ExportNames.speciesFolders(null, "Danainae", null, null, "Danaus chrysippus", false))
                .containsExactly("Danainae", "Danaus_chrysippus");
        assertThat(ExportNames.speciesFolders(null, null, null, null, "Danaus chrysippus", false))
                .containsExactly("Danaus_chrysippus");
    }

    @Test
    void targetsUseSlashesAndClashesWithoutCaseGetNumbersBeforeTheExtension() {
        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(
                photo(1, "Danaus_chrysippus_01.jpg", "Nymphalidae", "Danaus_chrysippus"),
                photo(2, "IMG_0001.JPG"),
                photo(3, "img_0001.jpg"),
                photo(4, "IMG_0001_2.JPG"),
                photo(5, "IMG 0001.jpg", "a"),
                photo(6, "IMG_0001.jpg", "A")));

        assertThat(targets).extracting(ExportNames.Target::rel).containsExactly(
                "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_01.jpg", "IMG_0001.JPG", "img_0001_2.jpg", "IMG_0001_2_2.JPG",
                "a/IMG_0001.jpg", "a/IMG_0001_2.jpg");
        assertThat(targets).extracting(ExportNames.Target::imageId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(targets).noneMatch(ExportNames.Target::renamed);
    }

    @Test
    void aNameThatWouldPassTheLimitIsShortenedKeepingItsNumberAndExtension() {
        String folder = "F".repeat(150);
        String name = "Danaus_chrysippus_" + "x".repeat(60) + "_03.jpg";

        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(photo(1, name, folder), photo(2, name, folder),
                photo(3, "Danaus_03.jpg", folder)));

        String first = targets.get(0).rel();
        assertThat(ROOT.length() + 1 + first.length() + ".part".length()).as("the .part the copy writes first fits too")
                .isEqualTo(ExportNames.MAX_PATH);
        assertThat(first).startsWith(folder + "/Danaus_chrysippus_").endsWith("_03.jpg");
        assertThat(targets.get(0).renamed()).isTrue();
        assertThat(targets.get(1).rel()).endsWith("_03_2.jpg").isNotEqualToIgnoringCase(first);
        assertThat(ROOT.length() + 1 + targets.get(1).rel().length() + ".part".length()).isEqualTo(ExportNames.MAX_PATH);
        assertThat(targets.get(2)).isEqualTo(new ExportNames.Target(3, folder + "/Danaus_03.jpg", false));
        assertThat(ExportNames.fileName(first)).isEqualTo(first.substring(folder.length() + 1));
    }

    @Test
    void aShortenedStemNeverEndsInAnUnderscoreDashDotOrSpace() {
        // room for a name of 17 characters: "Zizeeria_" + "_03" + ".webp" would be the plain cut
        String folder = "F".repeat(ExportNames.MAX_PATH - ExportNames.PART.length() - ROOT.length() - 1 - 1 - 17);

        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(photo(1, "Zizeeria_karsandra_03.webp", folder),
                photo(2, "Zizeeria-.karsandra_04.webp", folder), photo(3, "___________extra_05.webp", folder)));

        assertThat(targets).extracting(t -> ExportNames.fileName(t.rel())).containsExactly("Zizeeria_03.webp",
                "Zizeeria_04.webp", "_05.webp"); // a stem of nothing but "_" becomes one "_" (never empty)
        assertThat(targets).allMatch(ExportNames.Target::renamed);
    }

    @Test
    void aShortenedStemOfDotsOrSpacesNeverLeavesALeadingDot() {
        String folder = "F".repeat(ExportNames.MAX_PATH - ExportNames.PART.length() - ROOT.length() - 1 - 1 - 17);

        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(photo(1, "............extra_05.webp", folder),
                photo(2, ".-._\u2003\u00A0-._.-._extra_06.webp", folder)));

        assertThat(targets).extracting(t -> ExportNames.fileName(t.rel())).containsExactly("_05.webp", "_06.webp");
    }

    @Test
    void aCutNeverSplitsASurrogatePairAndACutThatEmptiesTheStemLeavesAnUnderscore() {
        // room for a name of 14 characters: 7 of stem, "_03" and ".jpg"
        String fourteen = "F".repeat(ExportNames.MAX_PATH - ExportNames.PART.length() - ROOT.length() - 1 - 1 - 14);
        // room for a name of 8 characters: 1 of stem
        String eight = "G".repeat(fourteen.length() + 6);

        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(
                photo(1, "Papilio\uD83E\uDD8Bpolytes_03.jpg", fourteen.substring(1)),
                photo(2, "\uD83E\uDD8B\uD83E\uDD8B_03.jpg", eight)));

        assertThat(targets).extracting(t -> ExportNames.fileName(t.rel())).containsExactly("Papilio_03.jpg", "_03.jpg");
        assertThat(targets).allMatch(ExportNames.Target::renamed);
    }

    @Test
    void manyEqualNamesInOneFolderAreNumberedInOrderQuickly() {
        List<ExportNames.Source> sources = new java.util.ArrayList<>();
        for (int i = 1; i <= 20_000; i++) {
            sources.add(photo(i, i % 2 == 0 ? "IMG.jpg" : "img.JPG"));
        }

        long start = System.nanoTime();
        List<ExportNames.Target> targets = ExportNames.targets(ROOT, sources);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(targets.get(0).rel()).isEqualTo("img.JPG");
        assertThat(targets.get(1).rel()).isEqualTo("IMG_2.jpg");
        assertThat(targets.get(19_999).rel()).isEqualTo("IMG_20000.jpg");
        assertThat(ms).as("not quadratic: " + ms + " ms").isLessThan(2_000);
    }

    @Test
    void theSpeciesListInTheExportFolderMustFitTooWithItsPart() {
        String fits = "/" + "x".repeat(236); // 237 + 1 + "species-list.csv.part" = 259
        String over = "/" + "x".repeat(237);
        List<String> reserved = List.of(ExportNames.CSV_IN_FOLDER, ExportNames.CSV_IN_FOLDER + ExportNames.PART);

        assertThat(ExportNames.targets(fits, List.of(photo(1, "a.jpg")), reserved)).hasSize(1);
        assertThatThrownBy(() -> ExportNames.targets(over, List.of(photo(1, "a.jpg")), reserved))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("path_too_long"));
        assertThat(ExportNames.targets(over, List.of(photo(1, "a.jpg")))).as("photos alone: no list").hasSize(1);
        assertThatThrownBy(() -> ExportNames.targets(over, List.of(photo(1, "a.jpg")), reserved, true))
                .as("in one folder already").hasMessage("The chosen folder's path is too long. Choose a folder nearer the top"
                        + " of the drive.");
        assertThatThrownBy(() -> ExportNames.targets("/" + "x".repeat(ExportNames.MAX_FOLDER), List.of(photo(1, "a.jpg")),
                List.of(), true)).hasMessage("The chosen folder's path is too long. Choose a folder nearer the top of the"
                        + " drive.");
        assertThatThrownBy(() -> ExportNames.targets(over, List.of(photo(1, "a.jpg", "Danaus")), reserved, false))
                .hasMessage(ExportErrors.PATH_TOO_LONG);
    }

    @Test
    void namesEqualWithoutCaseAreFoldedBeyondAscii() {
        String upper = "\u039F\u0394\u039F\u03A3"; // ΟΔΟΣ
        String medial = "\u03BF\u03B4\u03BF\u03C3"; // οδοσ, its lower case written with a medial sigma

        assertThat(ExportNames.firstFree(upper, Set.of(medial))).as("final sigma").isEqualTo(upper + "_2");
        assertThat(ExportNames.targets(ROOT, List.of(photo(1, upper + ".jpg"), photo(2, medial + ".jpg"))))
                .extracting(ExportNames.Target::rel).containsExactly(upper + ".jpg", medial + "_2.jpg");
    }

    @Test
    void whenEvenTheFoldersCannotFitNothingStarts() {
        String folder = "F".repeat(250);

        assertThatThrownBy(() -> ExportNames.targets(ROOT, List.of(photo(1, "a.jpg"), photo(2, "Danaus_03.jpg", folder))))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.code()).isEqualTo("path_too_long");
                    assertThat(e.getMessage()).isEqualTo("The chosen folder's path is too long for these folders. Choose a"
                            + " folder nearer the top of the drive, or All photos in one folder.");
                });
    }

    @Test
    void reservedNamesAtTheExportFolderTakeTheirPlaceWithoutCase() {
        assertThat(ExportNames.targets(ROOT, List.of(photo(1, "species-list.csv"), photo(2, "species-list.csv", "a")),
                List.of("Species-List.csv"))).extracting(ExportNames.Target::rel)
                .containsExactly("species-list_2.csv", "a/species-list.csv");
    }

    @Test
    void foldersThatDifferOnlyByCaseAreOneFolder() {
        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(photo(1, "x.jpg", "Danaus", "Danaus_a"),
                photo(2, "x.jpg", "danaus", "DANAUS_A")));

        assertThat(targets).extracting(ExportNames.Target::rel).containsExactly("Danaus/Danaus_a/x.jpg", "Danaus/Danaus_a/x_2.jpg");
    }

    @Test
    void aFolderPathPastWindowsDirectoryLimitIsRefused() {
        String fits = "F".repeat(ExportNames.MAX_FOLDER - ROOT.length() - 1);
        String over = "F".repeat(ExportNames.MAX_FOLDER - ROOT.length());

        assertThat(ExportNames.MAX_FOLDER).isEqualTo(247);
        assertThat(ExportNames.targets(ROOT, List.of(photo(1, "a.jpg", fits)))).hasSize(1);
        assertThatThrownBy(() -> ExportNames.targets(ROOT, List.of(photo(1, "a.jpg", over))))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("path_too_long"));
        assertThatThrownBy(() -> ExportNames.targets("/" + "x".repeat(ExportNames.MAX_FOLDER), List.of(photo(1, "a.jpg"))))
                .as("the export folder itself").isInstanceOf(AtlasException.class);
    }

    @Test
    void aShortenedNameThatBecomesADeviceNameGetsALeadingUnderscore() {
        // room for a name of 8 characters, then of 7
        String eight = "F".repeat(ExportNames.MAX_PATH - ROOT.length() - 1 - 1 - ".part".length() - 8);
        String seven = "G".repeat(eight.length() + 1);

        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(photo(1, "CON...x.jpg", eight),
                photo(2, "nul.jpg", eight), photo(3, "CONSTANTINE.jpg", seven)));

        assertThat(targets).extracting(t -> ExportNames.fileName(t.rel())).containsExactly("_CON.jpg", "_nul.jpg", "CO.jpg");
        assertThat(targets).extracting(ExportNames.Target::renamed).containsExactly(true, false, true);
    }

    @Test
    void foldersThatDifferOnlyByCaseShareTheirFirstSpellingAtEveryLevel() {
        List<ExportNames.Target> targets = ExportNames.targets(ROOT, List.of(photo(1, "x.jpg", "Danaus", "A"),
                photo(2, "y.jpg", "danaus", "B"), photo(3, "z.jpg", "DANAUS", "b")));

        assertThat(targets).extracting(ExportNames.Target::rel).containsExactly("Danaus/A/x.jpg", "Danaus/B/y.jpg",
                "Danaus/B/z.jpg");
    }
}
