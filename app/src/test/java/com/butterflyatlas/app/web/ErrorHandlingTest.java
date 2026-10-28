package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.library.LibraryDrive;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlingTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private Javalin app;
    @TempDir
    Path library;
    private final AtomicReference<Path> folder = new AtomicReference<>();
    private final AtomicLong free = new AtomicLong(Long.MAX_VALUE);

    @BeforeEach
    void start() {
        app = Javalin.create(config -> config.showJavalinBanner = false);
        folder.set(library);
        ErrorHandling.install(app, new LibraryDrive(() -> Optional.of(folder.get()), p -> free.get(), System::nanoTime));
        app.get("/api/db-failed", ctx -> {
            throw new DatabaseException("read failed", new SQLException("[SQLITE_IOERR_SHMOPEN] I/O error within xShmMap"));
        });
        app.get("/api/db-busy", ctx -> {
            throw new DatabaseException("write failed", new RuntimeException("wrapped",
                    new org.sqlite.SQLiteException("[SQLITE_BUSY] The database file is locked (database is locked)",
                            org.sqlite.SQLiteErrorCode.SQLITE_BUSY)));
        });
        app.get("/api/db-busy-snapshot", ctx -> {
            throw new DatabaseException("read failed", new org.sqlite.SQLiteException("[SQLITE_BUSY_SNAPSHOT]",
                    org.sqlite.SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT));
        });
        app.get("/api/boom", ctx -> {
            throw new IllegalStateException("secret detail");
        });
        app.get("/api/missing", ctx -> {
            throw AtlasException.notFound("No such taxon.");
        });
        app.get("/api/invalid", ctx -> {
            throw AtlasException.invalid("Type a search word.");
        });
        app.get("/api/setup-first", ctx -> {
            throw new AtlasException(AtlasException.Kind.SETUP_REQUIRED, "Choose where to keep your library first.");
        });
        app.get("/api/twice", ctx -> {
            throw new AtlasException(AtlasException.Kind.ALREADY_CONFIGURED, "Your library is already set up.");
        });
        app.get("/api/too-large", ctx -> {
            throw AtlasException.tooLarge("This file is larger than 300 MB.");
        });
        app.get("/api/not-a-photo", ctx -> {
            throw AtlasException.unsupported("HEIC photos aren't supported yet.");
        });
        app.get("/api/busy", ctx -> {
            throw AtlasException.busy("Busy making that picture. Try again in a moment.");
        });
        app.get("/api/filing", ctx -> {
            throw new AtlasException(AtlasException.Kind.CONFLICT, "import_filing", "Wait for filing to finish or pause it.");
        });
        app.get("/api/locked", ctx -> {
            throw new AtlasException(AtlasException.Kind.FORBIDDEN, "Butterfly Atlas can't open that folder.");
        });
        app.start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        app.stop();
        http.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void internalErrorsHideTheirDetails() throws Exception {
        HttpResponse<String> response = get("/api/boom");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("\"code\":\"internal\"").doesNotContain("secret detail");
    }

    @Test
    void aDatabaseFailureOnAFullDriveIs507DriveFullAndOnAMissingOne503DriveGone() throws Exception {
        assertThat(get("/api/db-failed").statusCode()).as("space to spare: the generic error").isEqualTo(500);

        free.set(0);
        HttpResponse<String> full = get("/api/db-failed");
        assertThat(full.statusCode()).isEqualTo(507);
        assertThat(full.body()).isEqualTo("{\"error\":{\"code\":\"drive_full\",\"message\":\"The drive with your library is"
                + " full, so Butterfly Atlas can't read or save anything. Free some space, then try again.\"}}");
        assertThat(get("/api/boom").statusCode()).as("not a storage failure").isEqualTo(500);

        folder.set(library.resolve("unplugged"));
        HttpResponse<String> gone = get("/api/db-failed");
        assertThat(gone.statusCode()).isEqualTo(503);
        assertThat(gone.body()).isEqualTo("{\"error\":{\"code\":\"drive_gone\",\"message\":\"The drive with your library"
                + " can't be found. Reconnect it, then try again.\"}}");
    }

    @Test
    void aDatabaseThatStayedBusyIs503BusyInWordsBeforeTheDriveIsLookedAt() throws Exception {
        folder.set(library.resolve("unplugged")); // a drive problem would otherwise be said
        for (String path : new String[] {"/api/db-busy", "/api/db-busy-snapshot"}) {
            HttpResponse<String> busy = get(path);
            assertThat(busy.statusCode()).isEqualTo(503);
            assertThat(busy.body()).isEqualTo("{\"error\":{\"code\":\"busy\",\"message\":\"Another window is saving"
                    + " changes. Try again in a moment.\"}}");
        }
        assertThat(get("/api/db-failed").statusCode()).as("other database failures as before").isEqualTo(503);
    }

    @Test
    void coreErrorsBecomeStatusesAndCodes() throws Exception {
        assertThat(get("/api/missing")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(r.body()).isEqualTo("{\"error\":{\"code\":\"not_found\",\"message\":\"No such taxon.\"}}");
        });
        assertThat(get("/api/invalid")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("\"code\":\"bad_request\"");
        });
        assertThat(get("/api/setup-first")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(409);
            assertThat(r.body()).contains("\"code\":\"setup_required\"");
        });
        assertThat(get("/api/twice")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(409);
            assertThat(r.body()).contains("\"code\":\"already_configured\"");
        });
    }

    @Test
    void aSpecificCodeIsUsedWithItsKindsStatus() throws Exception {
        assertThat(get("/api/filing")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(409);
            assertThat(r.body()).isEqualTo(
                    "{\"error\":{\"code\":\"import_filing\",\"message\":\"Wait for filing to finish or pause it.\"}}");
        });
        assertThat(get("/api/locked")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).contains("\"code\":\"forbidden\"");
        });
    }

    @Test
    void photoRefusalsBecome413And415() throws Exception {
        assertThat(get("/api/too-large")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(413);
            assertThat(r.body()).isEqualTo("{\"error\":{\"code\":\"too_large\",\"message\":\"This file is larger than 300 MB.\"}}");
        });
        assertThat(get("/api/not-a-photo")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(415);
            assertThat(r.body()).contains("\"code\":\"unsupported_photo\"", "HEIC photos aren't supported yet.");
        });
        assertThat(get("/api/busy")).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("\"code\":\"busy\"", "Try again in a moment.");
        });
    }

    @Test
    void unknownApiPathsAnswerInJson() throws Exception {
        HttpResponse<String> response = get("/api/nope");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"error\":{\"code\":\"not_found\",\"message\":\"There is nothing at /api/nope.\"}}");
    }

    @Test
    void unknownPagesOutsideTheApiAreAPlain404() throws Exception {
        HttpResponse<String> response = get("/no/such/page");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("Not found");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
    }
}
