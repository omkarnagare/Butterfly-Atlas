package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.sync.LibrarySync;
import com.butterflyatlas.app.sync.ManualTicker;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status and routes of one device at a time while running (one-device contract §2): {@code publish} is null while
 * publishing works, and {@code POST /api/library/publish} is 409 {@code not_stopped} then; once 5 tries failed it is
 * {@code stopped}, and Try again answers 202 with the status's {@code publish} after one attempt.
 */
class OneDeviceStatusApiTest {

    @TempDir
    Path home;

    private Path folder;

    @AfterEach
    void writable() throws Exception {
        if (folder != null && Files.isDirectory(folder)) {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void tryAgainIsRefusedUntilPublishingHasStoppedThenMakesOneAttempt() throws Exception {
        TestClock clock = new TestClock();
        ManualTicker ticker = new ManualTicker(clock);
        LibrarySync sync = new LibrarySync(home.resolve("config"), () -> "Mac", clock, millis -> { }, ticker);
        LibraryHolder holder = new LibraryHolder();
        AtlasServices services = TestServices.oneDevice(home, sync, holder);
        folder = home.resolve("Atlas");
        services.setup().setup(folder.toString());
        try (AtlasServer server = AtlasServer.start(0, services)) {
            Map<String, String> headers = Map.of("Origin", "http://127.0.0.1:" + server.port(), "Content-Type",
                    "application/json");
            RawHttp.Response fine = RawHttp.send(server.port(), "GET", "/api/status", headers, null);
            assertThat(fine.body()).contains("\"publish\":null", "\"openedElsewhere\":null");
            RawHttp.Response refused = RawHttp.send(server.port(), "POST", "/api/library/publish", headers, "{}");
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.body()).contains("\"code\":\"not_stopped\"");

            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
            holder.get().write(c -> {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('k', 'v')");
                }
                return null;
            });
            for (int i = 0; i < 5; i++) {
                clock.advance(LibrarySync.RETRY);
                ticker.runDue();
            }
            RawHttp.Response stopped = RawHttp.send(server.port(), "GET", "/api/status", headers, null);
            assertThat(stopped.body()).contains("\"publish\":{\"state\":\"stopped\"", "\"tries\":5");

            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
            RawHttp.Response again = RawHttp.send(server.port(), "POST", "/api/library/publish", headers, "{}");
            assertThat(again.status()).isEqualTo(202);
            assertThat(again.body()).isEqualTo("{\"publish\":null}");
        } finally {
            services.exports().close();
            services.thumbnails().close();
        }
    }
}
