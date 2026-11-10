package com.butterflyatlas.app.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Finds the browser process that holds our profile folder when this copy of the app did not start it (spec §3.2): after
 * a library-folder change the old windows stay open, so the new copy's first start hands its window to them; also after
 * a crash, or a quit whose windows did not close in time. On macOS and Linux Chrome and Edge keep a symbolic link
 * {@code SingletonLock} in the profile folder whose target is {@code <host>-<process ID>} ({@link #singletonLock()}); on
 * Windows there is no such link, and {@code WindowsNative} reads the command lines of the browser program's processes
 * instead. What is found is only a candidate: {@link BrowserWindows} checks that it runs and is the browser program.
 */
@FunctionalInterface
public interface ProfileHolder {

    /** The process holding {@code profile}, if one can be told; {@code browser} is the program the app starts. */
    OptionalLong find(Path browser, Path profile);

    /** Finds nothing (a desktop that never needs the process). */
    ProfileHolder NONE = (browser, profile) -> OptionalLong.empty();

    /** The process {@code SingletonLock} in the profile folder names (macOS and Linux). */
    static ProfileHolder singletonLock() {
        return (browser, profile) -> singletonLockPid(profile);
    }

    /**
     * The process ID at the end of the {@code SingletonLock} link's target (the host name may itself hold hyphens, so it is
     * what follows the last one); empty when there is no link, it is not a link, or its target ends in no number.
     */
    static OptionalLong singletonLockPid(Path profile) {
        Path lock = profile.resolve("SingletonLock");
        if (!Files.isSymbolicLink(lock)) {
            return OptionalLong.empty();
        }
        try {
            String target = Files.readSymbolicLink(lock).toString();
            int dash = target.lastIndexOf('-');
            String digits = target.substring(dash + 1);
            if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit) || digits.length() > 18) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(digits));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return OptionalLong.empty();
        }
    }
}
