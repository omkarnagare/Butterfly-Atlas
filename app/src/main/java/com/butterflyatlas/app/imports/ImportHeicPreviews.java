package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.PhotoProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Previews an import could not make when it read its items (Phase 7b contract §3–§4, audit 6 and 10): HEIC items read
 * while the computer's decoder was being checked or missing get theirs once it is available (at start, after Check again,
 * or when a reading ends), and a preview that took too long is made again once when it is asked for. Each one made (or
 * failed) stamps its item, so the grid sees it; {@link #making} says how many of an import's are under way. One at a
 * time, on a thread of its own, after the gallery's thumbnails and the viewer in the decoder's line.
 */
final class ImportHeicPreviews implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ImportHeicPreviews.class);

    /** A HEIC item read without a preview. */
    private record Missing(long id, String relPath, long size, String modifiedAt, String v, String metaJson) {
    }

    private final Supplier<Library> library;
    private final ImportStore store;
    private final ImportReader reader;
    private final Clock clock;
    private final Map<Long, AtomicInteger> making = new ConcurrentHashMap<>();
    /** Items whose preview is queued or being made (fixes J2: a second fill does not queue them again). */
    private final java.util.Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;

    ImportHeicPreviews(Supplier<Library> library, ImportStore store, ImportReader reader, Clock clock) {
        this.library = library;
        this.store = store;
        this.reader = reader;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "atlas-import-heic-previews");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** How many of this import's previews are being made now. */
    int making(long runId) {
        AtomicInteger n = making.get(runId);
        return n == null ? 0 : n.get();
    }

    /** Makes the missing HEIC previews of every open import, in the background (the decoder is available now). */
    void fillAll() {
        if (!HeicStatus.current().isAvailable()) {
            return;
        }
        try {
            for (ImportStore.Run run : store.runs()) {
                if (ImportStore.OPEN.equals(run.state())) {
                    fill(run.id());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not look for HEIC previews to make", e);
        }
    }

    /** Makes the missing HEIC previews of this import in the background, when the decoder is available now. */
    void fill(long runId) {
        if (!HeicStatus.current().isAvailable()) {
            return;
        }
        List<Missing> missing;
        Path data;
        try {
            data = library.get().folder();
            missing = library.get().database().read(c -> {
                List<Missing> items = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, file_size, modified_at, v, meta_json"
                        + " FROM import_item WHERE run_id = ? AND read_state = 'read' AND file_type = 'heic'"
                        + " AND v IS NOT NULL AND preview_failed = 0 AND outcome IS NULL ORDER BY id")) {
                    ps.setLong(1, runId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            items.add(new Missing(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                                    rs.getString(5), rs.getString(6)));
                        }
                    }
                }
                return items;
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not look for the HEIC previews of import {}", runId, e);
            return;
        }
        missing.removeIf(item -> Files.exists(ImportStore.previewFile(data, runId, item.id(), item.v()))
                || !queued.add(item.id()));
        if (missing.isEmpty()) {
            return;
        }
        AtomicInteger count = making.computeIfAbsent(runId, k -> new AtomicInteger());
        count.addAndGet(missing.size());
        for (Missing item : missing) {
            try {
                executor.execute(() -> {
                    try {
                        make(runId, item, PhotoProcessor.Use.IMPORT);
                    } finally {
                        queued.remove(item.id());
                        count.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                queued.remove(item.id());
                count.decrementAndGet(); // closed
            }
        }
    }

    /** A preview that took too long, made again once (it is marked failed first, so never twice), in the background. */
    void retryOnce(long runId, long itemId) {
        boolean claimed = library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET preview_failed = ?"
                    + " WHERE id = ? AND run_id = ? AND preview_failed = ?")) {
                ps.setInt(1, ImportReader.PREVIEW_FAILED);
                ps.setLong(2, itemId);
                ps.setLong(3, runId);
                ps.setInt(4, ImportReader.PREVIEW_TIMED_OUT);
                return ps.executeUpdate() > 0;
            }
        });
        if (!claimed) {
            return;
        }
        Missing item = library.get().database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, file_size, modified_at, v, meta_json"
                    + " FROM import_item WHERE id = ? AND read_state = 'read' AND v IS NOT NULL")) {
                ps.setLong(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Missing(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)) : null;
                }
            }
        });
        if (item == null) {
            return;
        }
        AtomicInteger count = making.computeIfAbsent(runId, k -> new AtomicInteger());
        count.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    make(runId, item, PhotoProcessor.Use.SHOWN);
                } finally {
                    count.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            count.decrementAndGet();
        }
    }

    /** Makes one preview from the item's file, if it is still as read, and records how it went on the item. */
    private void make(long runId, Missing item, PhotoProcessor.Use use) {
        try {
            ImportStore.Run run = store.run(runId);
            Path source = ImportStore.resolve(run.root(), item.relPath());
            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (ImportReader.look(attrs, ImportNames.WINDOWS, item.size(), item.modifiedAt()) != ImportReader.Look.OK) {
                return; // changed or gone: the next walk reads it again
            }
            PhotoFacts facts = PhotoFacts.fromJson(item.metaJson());
            Path preview = ImportStore.previewFile(library.get().folder(), runId, item.id(), item.v());
            if (Files.exists(preview)) {
                return; // made meanwhile (the reading, or an earlier fill): nothing to record
            }
            int failed = reader.preview(source, facts.format(), facts, preview, use);
            library.get().database().write(c -> {
                long seq = ImportStore.stamp(c, runId, clock.instant());
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET preview_failed = ?, seq = ?"
                        + " WHERE id = ? AND v = ?")) {
                    ps.setInt(1, failed);
                    ps.setLong(2, seq);
                    ps.setLong(3, item.id());
                    ps.setString(4, item.v());
                    return ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOG.info("Could not make the preview of import item {}: {}", item.id(), e.toString());
        }
    }

    /** Waits until no preview is being made (tests), at most {@code millis}. */
    boolean awaitIdle(long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (making.values().stream().anyMatch(n -> n.get() > 0)) {
            if (System.nanoTime() > until) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
