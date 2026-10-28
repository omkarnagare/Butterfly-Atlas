package com.butterflyatlas.app.window;

import com.butterflyatlas.app.testing.FakeProcess;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The app window (design §2, audit 2): the command, the profile, the fallback and when it is taken. */
class WindowLauncherTest {

    @TempDir
    Path config;

    private final TestClock clock = new TestClock();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicInteger keys = new AtomicInteger();
    private final List<List<String>> started = new ArrayList<>();
    private final List<URI> tabs = new ArrayList<>();
    private final List<String> console = new ArrayList<>();
    private final List<StartupFailure.Message> problems = new ArrayList<>();
    private FakeProcess process = new FakeProcess();
    private boolean tabWorks = true;
    private boolean startFails;
    private AtlasLog log;

    @BeforeEach
    void log() {
        log = AtlasLog.open(config, clock);
    }

    private WindowLauncher launcher(Optional<Path> browser) {
        return new WindowLauncher(browser, config.resolve("browser_profile"),
                () -> URI.create("http://127.0.0.1:7878/launch?k=key" + keys.incrementAndGet()),
                new LaunchFiles(config, clock), command -> {
            started.add(command);
            if (startFails) {
                throw new IOException("Permission denied");
            }
            return process;
        }, uri -> {
            tabs.add(uri);
            return tabWorks;
        }, console::add, log, nanos::get, problems::add);
    }

    private List<Path> redirects() throws IOException {
        try (java.util.stream.Stream<Path> all = Files.list(config.resolve("launch"))) {
            return all.sorted().toList();
        }
    }

    private String redirect(URI file) throws IOException {
        return Files.readString(Path.of(file));
    }

    @Test
    void theWindowIsTheBrowserInAppModeOnARedirectFileWithItsOwnProfile() throws Exception {
        WindowLauncher launcher = launcher(Optional.of(Path.of("/Applications/Google Chrome")));

        launcher.open();

        Path page = redirects().get(0);
        assertThat(started).containsExactly(List.of(Path.of("/Applications/Google Chrome").toString(), "--app=" + page.toUri(),
                "--user-data-dir=" + config.resolve("browser_profile"), "--no-first-run", "--no-default-browser-check"));
        assertThat(Files.readString(page)).contains("key1");
        assertThat(String.join(" ", started.get(0))).doesNotContain("key1").doesNotContain("k=");
        assertThat(config.resolve("browser_profile")).isDirectory();
        assertThat(launcher.mode()).isEqualTo(WindowLauncher.Mode.WINDOW);
        assertThat(tabs).isEmpty();
    }

    @Test
    void withNoBrowserTheDefaultBrowserOpensATabAndTheNoteIsLogged() throws Exception {
        WindowLauncher launcher = launcher(Optional.empty());

        launcher.open();

        assertThat(started).isEmpty();
        assertThat(tabs).hasSize(1);
        assertThat(tabs.get(0).getScheme()).isEqualTo("file");
        assertThat(redirect(tabs.get(0))).contains("key1");
        assertThat(launcher.mode()).isEqualTo(WindowLauncher.Mode.TAB);
        assertThat(Files.readString(log.file())).contains("No Edge or Chrome").doesNotContain("key1");
        assertThat(console).isEmpty();
    }

    @Test
    void aBrowserThatCannotBeStartedFallsBackAtOnceWithTheReasonInTheLog() throws Exception {
        startFails = true;
        WindowLauncher launcher = launcher(Optional.of(Path.of("/bin/chrome")));

        launcher.open();

        assertThat(tabs).hasSize(1);
        assertThat(launcher.mode()).isEqualTo(WindowLauncher.Mode.TAB);
        assertThat(Files.readString(log.file())).contains("Permission denied");
    }

    @Test
    void aBrowserThatExitsWithAnErrorWithinFiveSecondsFallsBack() throws Exception {
        WindowLauncher launcher = launcher(Optional.of(Path.of("/bin/chrome")));
        launcher.open();
        nanos.addAndGet(4_000_000_000L);

        process.exits(1);

        assertThat(tabs).hasSize(1);
        assertThat(redirect(tabs.get(0))).contains("key2").doesNotContain("key1"); // a fresh key, in a file of its own
        assertThat(redirects()).hasSize(2);
        assertThat(launcher.mode()).isEqualTo(WindowLauncher.Mode.TAB);
    }

    @Test
    void aBrowserThatExitsCleanlyOrLateIsNotAFailure() {
        WindowLauncher quick = launcher(Optional.of(Path.of("/bin/chrome")));
        quick.open();
        process.exits(0); // handed the window to a copy already running

        FakeProcess second = new FakeProcess();
        process = second;
        WindowLauncher late = launcher(Optional.of(Path.of("/bin/chrome")));
        late.open();
        nanos.addAndGet(5_000_000_001L);
        second.exits(137); // the user's window closed it much later

        assertThat(tabs).isEmpty();
        assertThat(quick.mode()).isEqualTo(WindowLauncher.Mode.WINDOW);
        assertThat(late.mode()).isEqualTo(WindowLauncher.Mode.WINDOW);
    }

    @Test
    void theFallbackIsTakenOnlyOnce() {
        WindowLauncher launcher = launcher(Optional.of(Path.of("/bin/chrome")));
        launcher.open();

        assertThat(launcher.fallBack("no window connected within 60 seconds")).isTrue();
        assertThat(launcher.fallBack("again")).isFalse();

        assertThat(tabs).hasSize(1);
    }

    @Test
    void whenEvenTheDefaultBrowserFailsTheConsoleGetsTheRedirectFileButNeverTheKey() throws Exception {
        tabWorks = false;
        WindowLauncher launcher = launcher(Optional.empty());

        launcher.open();

        assertThat(console).singleElement().asString().contains("Open " + redirects().get(0)).doesNotContain("key1");
        assertThat(Files.readString(log.file())).contains("default browser").doesNotContain("key1");
        assertThat(launcher.mode()).isEqualTo(WindowLauncher.Mode.TAB);
    }

    @Test
    void everyLaunchGetsAFileOfItsOwnAndNoCommandEverHoldsAKey() throws Exception {
        launcher(Optional.of(Path.of("/bin/chrome"))).open();
        launcher(Optional.of(Path.of("/bin/chrome"))).open();

        assertThat(redirects()).hasSize(2);
        assertThat(started.get(0).get(1)).isNotEqualTo(started.get(1).get(1));
        assertThat(started.stream().flatMap(List::stream)).noneMatch(arg -> arg.contains("key") || arg.contains("k="));
    }

    @Test
    void aFolderThatCannotBeWrittenIsToldInWordsAndNeverFallsBackToAKeyInTheCommand() throws Exception {
        Files.writeString(config.resolve("launch"), "a file where the folder should be");

        WindowLauncher window = launcher(Optional.of(Path.of("/bin/chrome")));
        window.open();
        WindowLauncher tab = launcher(Optional.empty());
        tab.open();

        assertThat(started).isEmpty();
        assertThat(tabs).isEmpty();
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0).what()).contains(config.toString());
        assertThat(Files.readString(log.file())).doesNotContain("key1");
    }

    @Test
    void awaitSettledWaitsForTheEarlyExitsFallbackAndGivesUpWhenTheBrowserStaysUp() throws Exception {
        WindowLauncher launcher = launcher(Optional.of(Path.of("/bin/chrome")));
        launcher.open();

        assertThat(launcher.awaitSettled(java.time.Duration.ofMillis(50))).as("still running: nothing to wait for").isFalse();
        Thread exiting = new Thread(() -> process.exits(1));
        exiting.start();

        assertThat(launcher.awaitSettled(java.time.Duration.ofSeconds(10))).isTrue();
        assertThat(tabs).hasSize(1); // the fallback is done by the time the wait ends
        assertThat(launcher.noWindow()).isFalse();
    }

    @Test
    void noWindowIsSaidWhenNothingCouldBeOpened() {
        tabWorks = false;
        WindowLauncher launcher = launcher(Optional.empty());

        launcher.open();

        assertThat(launcher.noWindow()).isTrue();
    }
}
