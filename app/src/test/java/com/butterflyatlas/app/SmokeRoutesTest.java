package com.butterflyatlas.app;

import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.web.AtlasServices;
import com.butterflyatlas.app.web.Session;
import com.butterflyatlas.app.window.QuitTimer;
import com.butterflyatlas.app.window.SessionKeys;
import com.butterflyatlas.app.window.WindowLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the packaging smoke test honest (design §15, item 11): every route the server registers is named in
 * {@code packaging/smoke/routes.txt}, which the smoke test calls against the built app, and every line of that file is a
 * route that exists. A route added later fails here until the smoke test's list has it.
 */
class SmokeRoutesTest {

    private static final Path LIST = Path.of("..", "packaging", "smoke", "routes.txt");

    @TempDir
    Path home;

    @Test
    void everyRegisteredRouteIsInTheSmokeTestsListAndEveryListedRouteIsRegistered() throws IOException {
        List<String> registered = registeredRoutes();
        List<String> listed = SmokeList.routes(Files.readAllLines(LIST));

        List<String> missing = new ArrayList<>(registered);
        missing.removeAll(listed);
        List<String> unknown = new ArrayList<>(listed);
        unknown.removeAll(registered);

        assertThat(missing).as("routes the server has that packaging/smoke/routes.txt does not call").isEmpty();
        assertThat(unknown).as("lines of packaging/smoke/routes.txt that are not routes of the server").isEmpty();
        assertThat(listed).as("a route listed twice").doesNotHaveDuplicates();
    }

    private List<String> registeredRoutes() {
        AtlasServices services = TestServices.unconfigured(home);
        QuitTimer quit = new QuitTimer(1, 60, true, System::nanoTime, why -> { }, why -> { });
        Session session = new Session(new SessionKeys(new TestClock()), quit, () -> WindowLauncher.Mode.WINDOW, () -> false,
                home.resolve("config"), home.resolve("config").resolve("logs"), Duration.ofMillis(100));
        try (AtlasServer server = AtlasServer.start(0, services, session)) {
            return server.routes();
        } finally {
            services.exports().close();
            services.thumbnails().close();
        }
    }
}
