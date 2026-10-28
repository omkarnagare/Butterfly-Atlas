package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.window.BrowserFinder;
import com.butterflyatlas.app.window.LaunchFiles;
import com.butterflyatlas.app.window.QuitTimer;
import com.butterflyatlas.app.window.SessionKeys;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A launch replayed with a real browser (multi-window design §1, §7): the app's server, a redirect file written as a window's
 * is, and Chrome or Edge, headless, on that file. The final page must be the app, not the no-session page: a redirect
 * after {@code /launch} lost the strict cookie in Chromium (the chain a {@code file:} page starts is cross-site), which no
 * test with a plain HTTP client could see. Skipped, and said so, where no Chrome or Edge is found.
 */
class BrowserLaunchTest {

    private static final long BROWSER_SECONDS = 60;

    @TempDir
    Path home;

    private AtlasServices services;
    private AtlasServer server;
    private final SessionKeys keys = new SessionKeys(Clock.systemUTC());

    @BeforeEach
    void start() {
        QuitTimer quit = new QuitTimer(1, 60, true, System::nanoTime, why -> { }, why -> { });
        services = TestServices.unconfigured(home);
        server = AtlasServer.start(0, services, new Session(keys, quit, () -> WindowLauncher.Mode.WINDOW, () -> false,
                home.resolve("config"), home.resolve("config").resolve("logs")));
    }

    @AfterEach
    void stop() {
        server.close();
        services.exports().close();
        services.thumbnails().close();
    }

    @Test
    void aWindowOpenedOnItsRedirectFileReachesTheAppNotTheNoSessionPage() throws Exception {
        BrowserFinder.Found found = BrowserFinder.system().find();
        Assumptions.assumeTrue(found.browser().isPresent(),
                "No Chrome or Edge was found, so the launch could not be replayed in a real browser on this computer");
        LaunchFiles files = new LaunchFiles(home.resolve("config"), Clock.systemUTC());
        keys.onRedeemed(files::redeemed);
        Path page = files.write(server.baseUri().resolve("launch?k=" + keys.newKey()));

        String dom = dumpDom(found.browser().get(), page);

        assertThat(dom).as("the final page is the app's own (its index.html), reached with the session cookie")
                .contains(appTitle())
                .doesNotContain(SessionGuard.MESSAGE)
                .doesNotContain("Open Butterfly Atlas from its icon.");
        assertThat(page).as("the key was redeemed, so its file is gone").doesNotExist();
    }

    /** The title of the {@code index.html} the server answers {@code /} with (the tests' own page comes first). */
    private static String appTitle() throws Exception {
        try (InputStream in = BrowserLaunchTest.class.getResourceAsStream("/ui/index.html")) {
            assertThat(in).as("an index.html under /ui on the classpath").isNotNull();
            String index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return index.substring(index.indexOf("<title>"), index.indexOf("</title>") + "</title>".length());
        }
    }

    /** The page's final DOM after the browser, headless with a profile of its own, has followed the file's chain. */
    private String dumpDom(Path browser, Path page) throws Exception {
        Path profile = Files.createDirectories(home.resolve("profile"));
        Process process = new ProcessBuilder(List.of(browser.toString(), "--headless=new", "--user-data-dir=" + profile,
                "--no-first-run", "--no-default-browser-check", "--virtual-time-budget=5000", "--dump-dom",
                page.toUri().toString())).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(out);
            } catch (Exception e) {
                // the process was stopped: what was read is kept
            }
        }, "browser-dom");
        reader.start();
        boolean ended = process.waitFor(BROWSER_SECONDS, TimeUnit.SECONDS);
        if (!ended) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        reader.join(Duration.ofSeconds(5).toMillis());
        assertThat(ended).as("the browser ended within " + BROWSER_SECONDS + " seconds").isTrue();
        return out.toString(StandardCharsets.UTF_8);
    }
}
