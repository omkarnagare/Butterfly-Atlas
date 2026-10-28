package com.butterflyatlas.app.window;

import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A start that cannot go on is shown, logged and exits with a code (audit 1, 4; contract §8). */
class StartupFailureTest {

    @TempDir
    Path config;

    @Test
    void thePageHasThePlainReasonAndWhatToDoAndEscapesWhatItQuotes() {
        StartupFailure.Message message = new StartupFailure.Message("Butterfly Atlas could not start",
                "Your library at /Users/<b>me</b>/Butterfly_Atlas & more could not be opened.", "Close other programs, then open it again.");

        String html = StartupFailure.html(message);

        assertThat(html).contains("<title>Butterfly Atlas could not start</title>")
                .contains("Your library at /Users/&lt;b&gt;me&lt;/b&gt;/Butterfly_Atlas &amp; more could not be opened.")
                .contains("Close other programs, then open it again.").doesNotContain("<b>me</b>");
    }

    @Test
    void reportingWritesThePageLogsItAndOpensItInTheDefaultBrowser() throws Exception {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        List<URI> opened = new ArrayList<>();

        Path page = StartupFailure.report(StartupFailure.portTaken(7878), config, log, uri -> {
            opened.add(uri);
            return true;
        });

        assertThat(page).isEqualTo(config.resolve("startup_problem.html"));
        assertThat(Files.readString(page)).contains("7878");
        assertThat(opened).containsExactly(page.toUri());
        assertThat(Files.readString(log.file())).contains("Another program is using port 7878");
    }

    @Test
    void whenEvenThePageCannotBeOpenedTheConsoleGetsTheReason() throws Exception {
        AtlasLog log = AtlasLog.open(config, new TestClock());
        StringBuilder console = new StringBuilder();

        StartupFailure.report(StartupFailure.damagedBundle("the taxonomy checksum does not match"), config, log, uri -> false,
                console::append);

        assertThat(console.toString()).contains("damaged").contains("the taxonomy checksum does not match")
                .contains(config.resolve("startup_problem.html").toString());
        assertThat(Files.readString(log.file())).contains("the taxonomy checksum does not match");
    }

    @Test
    void theMessagesNameWhatHappenedAndWhatToDo() {
        assertThat(StartupFailure.libraryTooNew("This library was made by a newer Butterfly Atlas (database version 13; this"
                + " one knows 12). Install the newer version.").todo()).contains("newer");
        assertThat(StartupFailure.libraryCannotOpen("/lib", "The drive with your library is full.").what())
                .contains("/lib").contains("full");
        assertThat(StartupFailure.folderNotWritable("/cfg").what()).contains("/cfg");
        StartupFailure.Message unexpected = StartupFailure.unexpected();
        assertThat(unexpected.what()).isEqualTo("Butterfly Atlas hit an unexpected problem. The details are in the log file.")
                .doesNotContain("Exception");
        assertThat(StartupFailure.notAnswering().what()).contains("already running").contains("not answering");
    }

    @Test
    void exitCodesAreOneForAFailedStartAndTwoForARunningCopyThatDoesNotAnswer() {
        assertThat(StartupFailure.EXIT_FAILED).isEqualTo(1);
        assertThat(StartupFailure.EXIT_NOT_ANSWERING).isEqualTo(2);
    }

    @Test
    void whenTheConfigFolderCannotBeWrittenThePageGoesToTheTempFolderOwnerOnlyUnderARandomNameAndIsOpened(@TempDir Path temp)
            throws Exception {
        Path blocked = Files.writeString(temp.resolve("not_a_folder"), "a file where the config folder should be");
        Path tmp = Files.createDirectories(temp.resolve("tmp"));
        AtlasLog log = AtlasLog.open(blocked.resolve("config"), new TestClock());
        List<URI> opened = new ArrayList<>();
        List<String> console = new ArrayList<>();

        Path page = StartupFailure.report(StartupFailure.folderNotWritable(blocked.resolve("config").toString()),
                blocked.resolve("config"), log, uri -> opened.add(uri), console::add, tmp);

        assertThat(page).isNotNull();
        assertThat(page.getParent()).isEqualTo(tmp);
        assertThat(page.getFileName().toString()).matches("butterfly_atlas_problem_.+\\.html");
        assertThat(Files.readString(page)).contains("cannot save its settings");
        assertThat(opened).containsExactly(page.toUri());
        assertThat(console).singleElement().asString().contains("cannot save its settings");
        if (page.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(page)).isEqualTo(java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"));
        }
        StartupFailure.report(StartupFailure.folderNotWritable("x"), blocked.resolve("config"), log, uri -> true,
                console::add, tmp);
        try (var all = Files.list(tmp)) {
            assertThat(all.map(Path::getFileName).distinct().count()).as("a name of its own each time").isEqualTo(2);
        }
    }

    @Test
    void whenNeitherFolderCanBeWrittenTheConsoleStillGetsTheReason(@TempDir Path temp) throws Exception {
        Path blocked = Files.writeString(temp.resolve("not_a_folder"), "a file");
        AtlasLog log = AtlasLog.open(blocked.resolve("config"), new TestClock());
        List<String> console = new ArrayList<>();

        Path page = StartupFailure.report(StartupFailure.unexpected(), blocked.resolve("config"), log, uri -> true,
                console::add, blocked.resolve("tmp"));

        assertThat(page).isNull();
        assertThat(console).singleElement().asString().contains("unexpected problem");
    }
}
