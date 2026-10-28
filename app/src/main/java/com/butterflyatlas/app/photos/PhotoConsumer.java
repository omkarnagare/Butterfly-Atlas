package com.butterflyatlas.app.photos;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * A feature that needs photo details (design §3.3). Fact consumers work from the stored snapshot
 * ({@code image_meta}) and never touch files; the ledger records which photo each has processed,
 * so a consumer added later, or one whose {@link #version()} rises, catches up on its own
 * ({@link ConsumerRunner}). The only pixel consumer is the thumbnail worker ({@link Thumbnails}).
 */
public interface PhotoConsumer {

    enum Kind { FACTS, PIXELS }

    /** Stored in {@code image_processing.consumer}; never changes once released. */
    String key();

    /** Raising it makes every photo be processed again. */
    int version();

    Kind kind();

    /** Processes a batch inside the caller's write transaction; throws only for the whole batch. */
    void apply(List<Subject> batch, Connection tx) throws SQLException;

    /** One photo: its id, its facts (from the snapshot) and its live file. */
    record Subject(long imageId, PhotoFacts facts, Path file) {
    }
}
