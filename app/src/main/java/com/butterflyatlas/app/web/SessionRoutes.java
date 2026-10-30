package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasVersion;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.window.SessionKeys;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Header;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.sse.SseClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The launch exchange, the session's facts and the window's stream (contract §2): {@code GET /launch?k=} swaps a one-time
 * key for the session cookie; {@code POST /launch/new} gives a second launch a key; {@code GET /api/session};
 * {@code POST /api/window} opens one more window (multi-window contract §2); {@code GET /api/session/stream}, whose open
 * streams drive the quit timer and carry {@code event: status} when the status changed ({@link StatusEvents}).
 */
final class SessionRoutes {

    /**
     * What a good launch key answers (multi-window design §1–§2): a page, never a redirect. A window is opened on a
     * {@code file:} page, so the chain that reaches {@code /launch} is cross-site and the browser keeps the strict cookie off
     * every request of it, a redirect included; a move that starts here, on {@code 127.0.0.1}, is same-origin and takes it.
     */
    static final String HOP_PAGE = "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<title>Butterfly Atlas</title><noscript><meta http-equiv=\"refresh\" content=\"0;url=/\"></noscript>"
            + "</head><body><script>location.replace(\"/\")</script></body></html>\n";

    static final String WINDOW_FAILED = "Butterfly Atlas couldn't open another window. Open it from its icon instead.";

    /** {@code GET /api/session}. */
    record Info(String mode, String version, String dataFolder, String configFolder, String logFolder, boolean lockWarning) {
    }

    record Launch(String launch) {
    }

    /** How often the status is compared with what the open streams were last told (design §6). */
    static final long STATUS_EVERY_MILLIS = 1000;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SessionRoutes.class);

    private final Session session;
    private final SetupService setup;
    private final StatusEvents events;
    private final ScheduledExecutorService pings = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "atlas-session-ping");
        thread.setDaemon(true);
        return thread;
    });

    /** {@code status}: what {@code GET /api/status} would answer, as JSON (built from memory). */
    SessionRoutes(Session session, SetupService setup, Supplier<String> status) {
        this(session, setup, status, () -> false);
    }

    /** As above; while {@code changing} holds (a library change runs) every stream is told every second. */
    SessionRoutes(Session session, SetupService setup, Supplier<String> status, java.util.function.BooleanSupplier changing) {
        this.session = session;
        this.setup = setup;
        this.events = new StatusEvents(status, changing);
        // on the pings' one thread, so a stream's ping and its status events are never written at once
        pings.scheduleAtFixedRate(() -> {
            try {
                events.check();
            } catch (RuntimeException | Error e) {
                LOG.warn("The windows' status check failed; it runs again in a second", e); // a throw would end the schedule
            }
        }, STATUS_EVERY_MILLIS, STATUS_EVERY_MILLIS, TimeUnit.MILLISECONDS);
    }

    void register(Javalin app) {
        SessionKeys keys = session.keys();
        app.get(SessionKeys.LAUNCH_PATH, ctx -> {
            if (!keys.redeem(ctx.queryParam(SessionKeys.KEY_PARAM))) {
                SessionGuard.page(ctx, SessionGuard.MESSAGE);
                return;
            }
            ctx.cookie(new Cookie(SessionKeys.cookieName(app.port()), keys.secret(), "/", -1, false, 0, true, null, null,
                    SameSite.STRICT));
            ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_STORE);
            ctx.header(Header.REFERRER_POLICY, "no-referrer");
            ctx.html(HOP_PAGE);
        });
        app.post(SessionKeys.NEW_LAUNCH_PATH, ctx -> {
            if (!keys.validInstanceKey(ctx.header(SessionKeys.KEY_HEADER))) {
                throw new ApiException(401, ErrorCodes.NO_SESSION, SessionGuard.MESSAGE);
            }
            if (session.closing().getAsBoolean()) {
                throw new ApiException(503, "closing", "Butterfly Atlas is closing.");
            }
            ctx.json(new Launch(keys.newKey()));
        });
        app.get("/api/session", ctx -> {
            SetupService.Status status = setup.status();
            ctx.json(new Info(session.mode().get().name().toLowerCase(java.util.Locale.ROOT), AtlasVersion.current(),
                    status.configured() ? status.dataFolder() : null, session.configFolder().toString(),
                    session.logFolder().toString(), setup.lockWarning()));
        });
        app.post("/api/window", ctx -> {
            if (!session.newWindow().open()) {
                throw new ApiException(500, "window_failed", WINDOW_FAILED);
            }
            ctx.status(204);
        });
        app.sse("/api/session/stream", this::stream);
    }

    /**
     * Answers at once (a comment line), then a status event (audit A11), then pings; the quit timer hears of the stream
     * while it is open, and the status events go to it until it ends.
     */
    private void stream(SseClient client) {
        client.keepAlive();
        session.quit().opened();
        AtomicBoolean ended = new AtomicBoolean();
        ScheduledFuture<?>[] ping = new ScheduledFuture<?>[1];
        StatusEvents.Stream told = () -> {
            if (!ended.get()) {
                client.sendEvent("status", "{}");
            }
        };
        Runnable end = () -> {
            if (ended.compareAndSet(false, true)) {
                if (ping[0] != null) {
                    ping[0].cancel(false);
                }
                events.closed(told);
                session.quit().closed();
            }
        };
        client.onClose(end);
        client.sendComment("open");
        pings.execute(() -> {
            if (!ended.get()) {
                events.opened(told);
            }
            if (ended.get()) {
                events.closed(told); // ended while it was being told
            }
        });
        long millis = session.ping().toMillis();
        ping[0] = pings.scheduleAtFixedRate(() -> {
            if (ended.get()) {
                return;
            }
            client.sendComment("ping");
            if (client.terminated()) {
                end.run(); // the write failed: the window is gone
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        if (ended.get()) {
            ping[0].cancel(false);
        }
    }

    void close() {
        pings.shutdownNow();
    }
}
