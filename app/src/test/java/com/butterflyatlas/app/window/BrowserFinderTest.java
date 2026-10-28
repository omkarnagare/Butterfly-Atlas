package com.butterflyatlas.app.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which browser opens the app window (design §2): the order per system, and the explicit choice for developers and tests. */
class BrowserFinderTest {

    @TempDir
    Path root;

    private Path exe(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private BrowserFinder finder(String os, Map<String, String> env, String explicit) {
        return new BrowserFinder(os, env, root.resolve("home"), explicit, Files::isExecutable);
    }

    @Test
    void windowsPrefersEdgeThenChromeInProgramFilesThenPerUser() throws Exception {
        Path pf = root.resolve("pf");
        Path pf86 = root.resolve("pf86");
        Path local = root.resolve("local");
        Map<String, String> env = Map.of("ProgramFiles", pf.toString(), "ProgramFiles(x86)", pf86.toString(),
                "LOCALAPPDATA", local.toString());
        Path chrome = exe("local/Google/Chrome/Application/chrome.exe");
        assertThat(finder("Windows 11", env, null).find().browser()).contains(chrome);

        Path perUserEdge = exe("local/Microsoft/Edge/Application/msedge.exe");
        assertThat(finder("Windows 11", env, null).find().browser()).contains(perUserEdge);

        Path edge86 = exe("pf86/Microsoft/Edge/Application/msedge.exe");
        assertThat(finder("Windows 11", env, null).find().browser()).contains(edge86);
    }

    @Test
    void windowsFindsEdgeWhenTheEnvironmentsNamesAreUpperCase() throws Exception {
        // Maven's Surefire starts the test JVM on Windows with every variable name upper-cased, and System.getenv()'s
        // map matches names exactly, so "ProgramFiles(x86)" found nothing (Edge was missed on a Windows 10 PC)
        Map<String, String> env = new HashMap<>(Map.of("PROGRAMFILES", root.resolve("pf").toString(),
                "PROGRAMFILES(X86)", root.resolve("pf86").toString(), "LOCALAPPDATA", root.resolve("local").toString()));
        Path edge86 = exe("pf86/Microsoft/Edge/Application/msedge.exe");

        assertThat(finder("Windows 10", env, null).find().browser()).contains(edge86);
    }

    @Test
    void macPrefersChromeThenEdgeInApplicationsThenTheUsersApplications() throws Exception {
        Path home = root.resolve("home");
        Path edge = exe("home/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        BrowserFinder mac = new BrowserFinder("Mac OS X", Map.of(), home, null, Files::isExecutable,
                java.util.List.of(root.resolve("Applications")));
        assertThat(mac.find().browser()).contains(edge);
        Path chrome = exe("home/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        assertThat(mac.find().browser()).contains(chrome);
        Path systemChrome = exe("Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        assertThat(mac.find().browser()).contains(systemChrome);
    }

    @Test
    void linuxWalksThePathForChromeThenChromiumThenEdge() throws Exception {
        Path bin1 = root.resolve("bin1");
        Path bin2 = root.resolve("bin2");
        Map<String, String> env = Map.of("PATH", bin1 + java.io.File.pathSeparator + bin2);
        assertThat(finder("Linux", env, null).find().browser()).isEmpty();

        Path edge = exe("bin2/microsoft-edge");
        assertThat(finder("Linux", env, null).find().browser()).contains(edge);
        Path chromium = exe("bin2/chromium-browser");
        assertThat(finder("Linux", env, null).find().browser()).contains(chromium);
        Path chrome = exe("bin1/google-chrome-stable");
        assertThat(finder("Linux", env, null).find().browser()).contains(chrome);
        Path chromeFirst = exe("bin2/google-chrome");
        assertThat(finder("Linux", env, null).find().browser()).contains(chromeFirst);
    }

    @Test
    void anExplicitBrowserWinsEverythingElse() throws Exception {
        Path mine = exe("mine/my-browser");
        Path chrome = exe("bin/google-chrome");
        Map<String, String> env = Map.of("PATH", chrome.getParent().toString());

        BrowserFinder.Found found = finder("Linux", env, mine.toString()).find();

        assertThat(found.browser()).contains(mine);
        assertThat(found.note()).isNull();
    }

    @Test
    void anExplicitBrowserThatIsNotThereIsSaidAndTheUsualSearchGoesOn() throws Exception {
        Path chrome = exe("bin/google-chrome");
        Map<String, String> env = Map.of("PATH", chrome.getParent().toString());

        BrowserFinder.Found found = finder("Linux", env, root.resolve("nowhere").toString()).find();

        assertThat(found.browser()).contains(chrome);
        assertThat(found.note()).contains("nowhere").contains("not a program");
    }

    @Test
    void theExplicitChoiceIsReadFromThePropertyThenTheEnvironment() {
        Map<String, String> env = new HashMap<>(Map.of("ATLAS_BROWSER", "/env/browser"));
        assertThat(BrowserFinder.explicit(() -> "/prop/browser", env::get)).isEqualTo("/prop/browser");
        assertThat(BrowserFinder.explicit(() -> " ", env::get)).isEqualTo("/env/browser");
        assertThat(BrowserFinder.explicit(() -> null, k -> null)).isNull();
    }
}
