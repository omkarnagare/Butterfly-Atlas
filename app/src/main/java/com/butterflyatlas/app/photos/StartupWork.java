package com.butterflyatlas.app.photos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What runs in the background once a library is open (design §7): removing uploads a crash left
 * in {@code .incoming}, waiting for a taxonomy update the app's closing cut short to finish or pause (5b contract §4),
 * the rescan, catching up photo details and consumers (and queueing
 * missing thumbnails), removing Recently deleted entries older than 30 days, removing only the files in
 * {@code .deleted} a purge left behind (fail-safe: see {@link DeletedSweep#sweep()}; any other file there is
 * kept), then thumbnails nothing needs. A failing step is logged and the next one still runs. Imports may file
 * (filesSettled) once the steps that move or register photo files are over, before thumbnails are tidied.
 */
public final class StartupWork {

    private static final Logger LOG = LoggerFactory.getLogger(StartupWork.class);

    private final Rescan rescan;
    private final PhotoStore photos;
    private final ConsumerRunner processing;
    private final Thumbnails thumbnails;
    private final Runnable awaitUpdate;
    private final Runnable filesSettled;

    public StartupWork(Rescan rescan, PhotoStore photos, ConsumerRunner processing, Thumbnails thumbnails) {
        this(rescan, photos, processing, thumbnails, () -> { });
    }

    /**
     * @param filesSettled run once the steps that move or register photo files are over (whether they worked or not);
     *                     Main lets imports start then
     */
    public StartupWork(Rescan rescan, PhotoStore photos, ConsumerRunner processing, Thumbnails thumbnails,
                       Runnable filesSettled) {
        this(rescan, photos, processing, thumbnails, () -> { }, filesSettled);
    }

    /**
     * @param awaitUpdate waits while a taxonomy update continued at startup runs (until it is done or paused); the
     *                       rescan and the other photo-file steps come after it
     * @param filesSettled   run once the steps that move or register photo files are over (whether they worked or not);
     *                       Main lets imports start then
     */
    public StartupWork(Rescan rescan, PhotoStore photos, ConsumerRunner processing, Thumbnails thumbnails,
                       Runnable awaitUpdate, Runnable filesSettled) {
        this.rescan = rescan;
        this.photos = photos;
        this.processing = processing;
        this.thumbnails = thumbnails;
        this.awaitUpdate = awaitUpdate;
        this.filesSettled = filesSettled;
    }

    public void run() {
        try {
            step("remove unfinished uploads", photos::cleanIncoming);
            step("finish the taxonomy update", awaitUpdate);
            step("check the photo folders", rescan::run);
            step("catch up photo details", processing::run);
            step("empty old entries from Recently deleted", () -> photos.purgeOlderThan(PhotoQueries.KEEP_DELETED));
            step("remove files left in Recently deleted", photos::sweepDeleted);
        } finally {
            step("let imports start", filesSettled);
        }
        step("remove unused thumbnails", thumbnails::cleanOrphans);
    }

    /** Runs {@link #run()} on a background (daemon) thread. */
    public Thread start() {
        Thread thread = new Thread(this::run, "atlas-startup");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void step(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.warn("Could not {} at startup", what, e);
        }
    }
}
