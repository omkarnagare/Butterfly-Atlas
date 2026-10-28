package com.butterflyatlas.app.window;

import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The redirect files a window is opened on (the owner's shared-computer rule: a launch key is never in a program's
 * arguments): owner-only from the first byte, random names, deleted when the key is used or has expired, emptied at the
 * start and at the quit.
 */
class LaunchFilesTest {

    private static final URI TARGET = URI.create("http://127.0.0.1:7878/launch?k=abcDEF-_123");

    @TempDir
    Path config;

    private final TestClock clock = new TestClock();
    private LaunchFiles files;

    @BeforeEach
    void files() {
        files = new LaunchFiles(config, clock);
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private long count() throws IOException {
        try (Stream<Path> all = Files.list(config.resolve("launch"))) {
            return all.count();
        }
    }

    @Test
    void theFileIsOwnerOnlyInAnOwnerOnlyFolderWithARandomName() throws Exception {
        assumeTrue(posix(config));

        Path file = files.write(TARGET);

        assertThat(file.getParent()).isEqualTo(config.resolve("launch"));
        assertThat(file.getFileName().toString()).matches("open_[0-9a-f]{32}\\.html");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void theFileRedirectsToTheLaunchAddressWithAndWithoutScripts() throws Exception {
        Path file = files.write(URI.create("http://127.0.0.1:7878/launch?k=abc&notice=already-running"));

        String page = Files.readString(file);

        assertThat(page).startsWith("<!doctype html><meta charset=utf-8><meta name=referrer content=no-referrer>")
                .contains("<title>Opening Butterfly Atlas</title>")
                .contains("<script>location.replace(\"http:\\/\\/127.0.0.1:7878\\/launch?k=abc&notice=already-running\")</script>")
                .contains("<noscript><meta http-equiv=refresh content=\"0;url=http://127.0.0.1:7878/launch?k=abc&amp;notice=already-running\"></noscript>");
    }

    @Test
    void everyLaunchGetsItsOwnFile() throws Exception {
        Path first = files.write(TARGET);
        Path second = files.write(TARGET);

        assertThat(first).isNotEqualTo(second);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void theFileIsDeletedWhenItsKeyIsUsedAndOnlyThen() throws Exception {
        Path used = files.write(TARGET);
        Path other = files.write(URI.create("http://127.0.0.1:7878/launch?k=other"));

        files.redeemed("abcDEF-_123");
        files.redeemed("not-a-key-of-ours");

        assertThat(used).doesNotExist();
        assertThat(other).exists();
    }

    @Test
    void aFileIsDeletedOnceItsKeyHasExpired() throws Exception {
        Path file = files.write(TARGET);

        clock.advance(SessionKeys.LIFE.minusSeconds(1));
        files.sweep();
        assertThat(file).exists();
        clock.advance(Duration.ofSeconds(2));
        files.sweep();

        assertThat(file).doesNotExist();
    }

    @Test
    void aFileAnotherCopyWroteIsSweptByItsAge() throws Exception {
        LaunchFiles real = new LaunchFiles(config, Clock.systemUTC());
        Path young = real.write(TARGET);
        Path old = new LaunchFiles(config, Clock.systemUTC()).write(TARGET);
        Files.setLastModifiedTime(old, FileTime.from(Clock.systemUTC().instant().minusSeconds(120)));

        real.sweep();

        assertThat(young).exists();
        assertThat(old).doesNotExist();
    }

    @Test
    void clearEmptiesTheFolder() throws Exception {
        files.write(TARGET);
        files.write(TARGET);

        files.clear();

        assertThat(count()).isZero();
        files.clear(); // and a folder that is not there is no problem
        Files.delete(config.resolve("launch"));
        files.clear();
    }

    @Test
    void aFolderThatCannotBeWrittenIsToldAndNothingIsWrittenElsewhere() throws Exception {
        assumeTrue(posix(config) && !"root".equals(System.getProperty("user.name")));
        Files.setPosixFilePermissions(config, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assertThatThrownBy(() -> files.write(TARGET)).isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
