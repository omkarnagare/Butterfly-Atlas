package com.butterflyatlas.app.window.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Chrome's {@code SingletonLock} in the profile folder names the process that holds the profile (macOS and Linux). */
class ProfileHolderTest {

    @TempDir
    Path profile;

    private static final Path CHROME = Path.of("/chrome");

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Chrome's SingletonLock is a symbolic link only on macOS and Linux")
    void theLockLinkNamesTheHostAndTheProcessId() throws Exception {
        Files.createSymbolicLink(profile.resolve("SingletonLock"), Path.of("omkars-mac-book.local-4242"));

        assertThat(ProfileHolder.singletonLock().find(CHROME, profile)).hasValue(4242);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Chrome's SingletonLock is a symbolic link only on macOS and Linux")
    void aLinkThatNamesNoProcessIdFindsNothing() throws Exception {
        Files.createSymbolicLink(profile.resolve("SingletonLock"), Path.of("somehost-notanumber"));

        assertThat(ProfileHolder.singletonLock().find(CHROME, profile)).isEmpty();
    }

    @Test
    void noLockOrAPlainFileFindsNothing() throws Exception {
        assertThat(ProfileHolder.singletonLock().find(CHROME, profile)).isEmpty();
        assertThat(ProfileHolder.singletonLock().find(CHROME, profile.resolve("missing"))).isEmpty();

        Files.writeString(profile.resolve("SingletonLock"), "host-12");
        assertThat(ProfileHolder.singletonLock().find(CHROME, profile)).isEmpty();
    }
}
