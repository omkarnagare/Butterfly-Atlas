package com.butterflyatlas.app.photos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every fact consumer, applied inline to photos as they come in (Add photos, filing an import, a rescan's new and edited
 * photos) inside the caller's write transaction, each marked done in the ledger there, so a new photo has its size,
 * orientation and place at once instead of at the next start. A consumer that fails with an unchecked exception (its
 * data cannot be read, say) is logged (with its stack trace the first time only) and left pending for the startup
 * catch-up; it never stops the photo going in.
 */
public final class FactConsumers {

    private static final Logger LOG = LoggerFactory.getLogger(FactConsumers.class);
    /** Consumers whose failure has been logged with its stack trace (once a run; afterwards one line a call). */
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final List<PhotoConsumer> consumers;
    private final ProcessingLedger ledger;

    public FactConsumers(List<PhotoConsumer> consumers, ProcessingLedger ledger) {
        for (PhotoConsumer consumer : consumers) {
            if (consumer.kind() != PhotoConsumer.Kind.FACTS) {
                throw new IllegalArgumentException(consumer.key() + " is not a fact consumer");
            }
        }
        this.consumers = List.copyOf(consumers);
        this.ledger = ledger;
    }

    /** Applies every consumer to the subjects (their facts must be known) and marks each done for them. */
    public void applyAll(Connection tx, List<PhotoConsumer.Subject> subjects) throws SQLException {
        if (subjects.isEmpty()) {
            return;
        }
        List<Long> ids = subjects.stream().map(PhotoConsumer.Subject::imageId).toList();
        for (PhotoConsumer consumer : consumers) {
            try {
                consumer.apply(subjects, tx);
            } catch (RuntimeException e) {
                if (TRACED.add(consumer.key())) {
                    LOG.warn("{} could not process photos {} now; the next start catches up", consumer.key(), ids, e);
                } else {
                    LOG.warn("{} could not process photos {} now ({}); the next start catches up", consumer.key(), ids,
                            e.getMessage());
                }
                continue;
            }
            ledger.markDone(tx, ids, consumer.key(), consumer.version());
        }
    }
}
