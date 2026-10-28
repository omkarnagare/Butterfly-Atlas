package com.butterflyatlas.app.exports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Remove's rule for one written file (4b review S1, item 5): ours only while its size and time still match. */
class ExportCleanupTest {

    private static final long WRITTEN = 1_791_890_603_000L;

    @TempDir
    Path dir;

    private Path folder;
    private Path file;

    @BeforeEach
    void write() throws Exception {
        folder = Files.createDirectories(dir.resolve("Butterfly_Atlas_export_2026-10-09"));
        file = Files.writeString(Files.createDirectories(folder.resolve("Danaus")).resolve("a.jpg"), "copy");
    }

    /** How many were kept when the file's time is {@code at} and the record says {@code bytes}, {@code mtime}. */
    private int remove(long at, long bytes, Long mtime) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(at));
        return ExportCleanup.remove(folder, List.of(new ExportStore.Written("Danaus/a.jpg", bytes, mtime)),
                List.of("Danaus/a.jpg"), List.of(), null);
    }

    @Test
    void theSameSizeAndATimeWithinTwoSecondsIsOurs() throws Exception {
        for (long offset : new long[] {0, 400, -400, 1_900, -1_900, 2_000, -2_000}) {
            write();
            assertThat(remove(WRITTEN + offset, 4, WRITTEN)).as("offset " + offset).isZero();
            assertThat(file).doesNotExist();
        }
        assertThat(folder).as("emptied, so removed").doesNotExist();
    }

    @Test
    void aTimeMovedThreeSecondsOrMoreIsKeptEvenAtTheSameSize() throws Exception {
        assertThat(remove(WRITTEN + 2_001, 4, WRITTEN)).as("just past the slack").isEqualTo(1);
        assertThat(remove(WRITTEN - 2_001, 4, WRITTEN)).isEqualTo(1);
        assertThat(remove(WRITTEN + 3_000, 4, WRITTEN)).isEqualTo(1);
        assertThat(file).hasContent("copy");
        assertThat(remove(WRITTEN + 10_000, 4, WRITTEN)).as("only the time changed").isEqualTo(1);
        assertThat(file).exists();
    }

    @Test
    void anUnknownTimeOrAnotherSizeIsKept() throws Exception {
        assertThat(remove(WRITTEN, 4, null)).as("no time recorded: not size alone").isEqualTo(1);
        assertThat(remove(WRITTEN, 5, WRITTEN)).isEqualTo(1);
        assertThat(file).exists();
    }

    @Test
    void aFileGoneWithItsFolderIsNotCounted() throws Exception {
        Files.delete(file);
        Files.delete(folder.resolve("Danaus"));

        assertThat(ExportCleanup.remove(folder, List.of(new ExportStore.Written("Danaus/a.jpg", 4, WRITTEN)),
                List.of("Danaus/a.jpg"), List.of(), null)).isZero();
        assertThat(folder).as("emptied, so removed").doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileGoneBehindAFolderSwappedForALinkIsCounted() throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("Elsewhere"));
        Files.delete(file);
        Files.delete(folder.resolve("Danaus"));
        try {
            Files.createSymbolicLink(folder.resolve("Danaus"), elsewhere);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "no symbolic links here");
        }

        assertThat(ExportCleanup.remove(folder, List.of(new ExportStore.Written("Danaus/a.jpg", 4, WRITTEN)),
                List.of("Danaus/a.jpg"), List.of(), null)).as("cannot be said to be gone").isEqualTo(1);
        assertThat(Files.isSymbolicLink(folder.resolve("Danaus"))).isTrue();
    }

    @Test
    void foldersWithNamesBeyondAsciiAreRemovedToo() throws Exception {
        Path deep = Files.createDirectories(folder.resolve("Gr\u00E4fin").resolve("\u00C9toile_\u03B2"));
        Path copy = Files.writeString(deep.resolve("p\u00E9_01.jpg"), "copy");
        Files.setLastModifiedTime(copy, FileTime.fromMillis(WRITTEN));
        Files.setLastModifiedTime(file, FileTime.fromMillis(WRITTEN));

        assertThat(ExportCleanup.remove(folder, List.of(new ExportStore.Written("Danaus/a.jpg", 4, WRITTEN),
                new ExportStore.Written("Gr\u00E4fin/\u00C9toile_\u03B2/p\u00E9_01.jpg", 4, WRITTEN)),
                List.of("Danaus/a.jpg", "Gr\u00E4fin/\u00C9toile_\u03B2/p\u00E9_01.jpg"), List.of(), null)).isZero();
        assertThat(folder).doesNotExist();
    }

    @Test
    void aFileAlreadyGoneIsNotCounted() throws Exception {
        Files.delete(file);

        assertThat(ExportCleanup.remove(folder, List.of(new ExportStore.Written("Danaus/a.jpg", 4, WRITTEN)),
                List.of("Danaus/a.jpg"), List.of(), null)).isZero();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileThatCannotBeReadIsKeptAndCounted() throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(WRITTEN));
        Path danaus = folder.resolve("Danaus");
        Files.setPosixFilePermissions(danaus, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(danaus), "running as a user who can read anything");
            assertThat(ExportCleanup.remove(folder, List.of(new ExportStore.Written("Danaus/a.jpg", 4, WRITTEN)),
                    List.of("Danaus/a.jpg"), List.of(), null)).isEqualTo(1);
        } finally {
            Files.setPosixFilePermissions(danaus, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(file).hasContent("copy");
    }

    @Test
    void theSpeciesListFollowsTheSameRule() throws Exception {
        Path csv = Files.writeString(folder.resolve(ExportNames.CSV_IN_FOLDER), "\uFEFFFamily\r\n");
        Files.setLastModifiedTime(csv, FileTime.fromMillis(WRITTEN));
        long size = Files.size(csv);

        assertThat(ExportCleanup.remove(folder, List.of(), List.of(), List.of(),
                new ExportStore.Written(ExportNames.CSV_IN_FOLDER, size + 1, WRITTEN))).isEqualTo(1);
        assertThat(csv).exists();
        assertThat(ExportCleanup.remove(folder, List.of(), List.of(), List.of(),
                new ExportStore.Written(ExportNames.CSV_IN_FOLDER, size, WRITTEN))).isZero();
        assertThat(csv).doesNotExist();
    }
}
