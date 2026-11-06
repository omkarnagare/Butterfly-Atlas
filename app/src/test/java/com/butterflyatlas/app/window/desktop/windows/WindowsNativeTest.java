package com.butterflyatlas.app.window.desktop.windows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of {@link WindowsNative} that are plain Java, so they are checked on every OS: which command line is the
 * browser process on our profile, and the values the taskbar is given.
 */
class WindowsNativeTest {

    private static final Path PROFILE = Path.of("C:\\Users\\a\\AppData\\Roaming\\Butterfly Atlas\\browser_profile");
    private static final String EDGE = "\"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe\"";

    @Test
    void theBrowserProcessNamesOurProfileQuotedAsJavaQuotesIt() {
        String line = EDGE + " --app=file:///C:/x.html \"--user-data-dir=" + PROFILE + "\" --no-first-run";

        assertThat(WindowsNative.browserOnProfile(line, PROFILE)).isTrue();
    }

    @Test
    void anUnquotedProfileAndAnyCaseAreTheSame() {
        Path plain = Path.of("C:\\Users\\a\\config\\browser_profile");

        assertThat(WindowsNative.browserOnProfile(EDGE + " --user-data-dir=C:\\USERS\\A\\Config\\Browser_Profile",
                plain)).isTrue();
        assertThat(WindowsNative.browserOnProfile(EDGE + " --user-data-dir=C:\\Users\\a\\config\\browser_profile\\",
                plain)).as("a trailing separator").isTrue();
    }

    @Test
    void aHelperProcessIsNotTheBrowserProcess() {
        String line = EDGE + " --type=renderer \"--user-data-dir=" + PROFILE + "\" --lang=en-GB";

        assertThat(WindowsNative.browserOnProfile(line, PROFILE)).isFalse();
    }

    @Test
    void anotherProfileIsNotOurs() {
        assertThat(WindowsNative.browserOnProfile(EDGE + " \"--user-data-dir=" + PROFILE + "2\"", PROFILE)).isFalse();
        assertThat(WindowsNative.browserOnProfile(EDGE + " --profile-directory=Default", PROFILE)).isFalse();
        assertThat(WindowsNative.browserOnProfile("", PROFILE)).isFalse();
    }

    @Test
    void theCommandLineIsSplitAsWindowsSplitsIt() {
        assertThat(WindowsNative.arguments("a \"b c\" d\"e f\"g  h")).containsExactly("a", "b c", "de fg", "h");
        assertThat(WindowsNative.arguments("  ")).isEmpty();
    }

    @Test
    void theTaskbarIsGivenTheIdTheQuotedProgramItsNameAndItsIcon() {
        Path app = Path.of("C:\\Users\\a\\AppData\\Local\\Programs\\Butterfly_Atlas\\Butterfly Atlas.exe");

        assertThat(WindowsNative.values(app)).containsExactly(
                List.of(WindowsNative.Property.ID.name(), "WeAreNaturalists.ButterflyAtlas"),
                List.of(WindowsNative.Property.RELAUNCH_COMMAND.name(), "\"" + app + "\""),
                List.of(WindowsNative.Property.RELAUNCH_DISPLAY_NAME.name(), "Butterfly Atlas"),
                List.of(WindowsNative.Property.RELAUNCH_ICON.name(), app + ",0"));
    }

    @Test
    void thePropertyKeysAreTheShellsAppUserModelKeys() {
        assertThat(WindowsNative.Property.ID.pid()).isEqualTo(5);
        assertThat(WindowsNative.Property.RELAUNCH_COMMAND.pid()).isEqualTo(2);
        assertThat(WindowsNative.Property.RELAUNCH_ICON.pid()).isEqualTo(3);
        assertThat(WindowsNative.Property.RELAUNCH_DISPLAY_NAME.pid()).isEqualTo(4);
        assertThat(WindowsNative.APP_USER_MODEL_FMTID).isEqualTo("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}");
    }
}
