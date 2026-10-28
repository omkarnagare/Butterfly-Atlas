package com.butterflyatlas.app.web;

import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import io.javalin.Javalin;

import java.time.Duration;

/**
 * {@code POST /api/heic/check} (Phase 7b contract §3, audit 11): Check again in Settings → About and the import bar.
 * Checks the computer's HEIC decoder afresh (a check already running is joined, never doubled) and answers its
 * {@code HeicStatus}, waiting at most {@value #WAIT_SECONDS} s; a check still running then answers {@code checking},
 * and the page keeps polling {@code /api/status}. When the decoder becomes available, the HEIC photos without a
 * thumbnail are queued and open imports' HEIC previews are made, in the background.
 */
final class HeicRoutes {

    static final long WAIT_SECONDS = 30;

    private final HeicDecoders heic;

    HeicRoutes(HeicDecoders heic) {
        this.heic = heic;
    }

    void register(Javalin app) {
        app.post("/api/heic/check", ctx -> ctx.json(heic.checkAndWait(Duration.ofSeconds(WAIT_SECONDS))));
    }
}
