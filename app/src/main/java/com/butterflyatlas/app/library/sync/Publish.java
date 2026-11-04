package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.OnlineCopy;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Publishing a snapshot of the working copy into the library folder (one-device design §5):
 * <ol>
 *   <li>{@link OnlineCopy#copy} (as a backup's database) into {@code atlas.db.part} in the library</li>
 *   <li>the copy's {@code generation} set to the working copy's plus one and its {@code dirty} to 0, then checked
 *   ({@code quick_check})</li>
 *   <li>the marker read again: when it names another device, it stops here and the {@code .part} goes</li>
 *   <li>the {@code .part} renamed over {@code atlas.db}, tried again for a moment when it is refused ({@link Replace})
 *   (and the {@code -wal} and {@code -shm} an in-place opening left
 *   deleted)</li>
 *   <li>in the working copy, {@code generation} the new number, {@code published_at} now and {@code dirty} 0, unless a
 *   write happened during steps 1–4, when {@code dirty} stays 1</li>
 *   <li>the marker written with the new {@code generation} and {@code seen}</li>
 * </ol>
 */
public final class Publish {

    private static final Logger LOG = LoggerFactory.getLogger(Publish.class);

    /** What a publish ended with. */
    public sealed interface Result permits Published, NotOurs {
    }

    /** It published {@code generation}; {@code dirty}: a write happened meanwhile, so another publish should follow. */
    public record Published(long generation, boolean dirty) implements Result {
    }

    /** Step 3 found the marker naming another device: nothing was published (§6). */
    public record NotOurs(Marker marker) implements Result {
    }

    /** The marker couldn't be read twice in a row (§10): publishing waits. */
    public static final class MarkerUnreadable extends IOException {
        MarkerUnreadable(String problem) {
            super("The library's device marker couldn't be read: " + problem);
        }
    }

    /** The snapshot's name while it is written. */
    public static final String PART = Database.FILE_NAME + Library.PART;

    private Publish() {
    }

    /**
     * Publishes the working copy {@code working} into {@code library} as this {@code device}, which has had it open since
     * {@code since}, at {@code now}. {@code generation}: the number to publish (null: the working copy's plus one; a
     * conversion publishes 1). {@code afterCopy} runs between steps 2 and 3 (tests). Throws when a step fails: the working
     * copy keeps {@code dirty = 1} and the library's {@code atlas.db} is as it was (or already the new snapshot when only the
     * last steps failed).
     */
    public static Result run(Database working, Path library, Device device, Instant since, Instant now, Long generation,
                             MarkerFile.Pause pause, Runnable afterCopy) throws IOException, SQLException {
        long begun = working.writes();
        boolean writing = working.writing();
        SyncState state = SyncState.read(working);
        long next = generation != null ? generation : state.generation() + 1;
        Path part = library.resolve(PART);
        Files.deleteIfExists(part);
        try {
            OnlineCopy.copy(working, part);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + part.toAbsolutePath())) {
                c.setAutoCommit(false);
                SyncState.set(c, SyncState.GENERATION, Long.toString(next));
                SyncState.set(c, SyncState.DIRTY, "0");
                SyncState.set(c, SyncState.PUBLISHED_AT, now.toString());
                c.commit();
            }
            WorkingCopy.check(part);
            afterCopy.run();
            MarkerFile.Read read = MarkerFile.readSettled(library, pause);
            if (read.unreadable()) {
                throw new MarkerUnreadable(read.problem());
            }
            if (read.marker() != null && !read.marker().names(device.id())) {
                LOG.warn("The library at {} is in use on another device ({}); nothing was published", library,
                        read.marker().name());
                return new NotOurs(read.marker());
            }
            Replace.replace(part, library.resolve(Database.FILE_NAME));
        } finally {
            Files.deleteIfExists(part);
        }
        for (String log : new String[] {"-wal", "-shm"}) {
            try {
                Files.deleteIfExists(library.resolve(Database.FILE_NAME + log));
            } catch (IOException e) {
                LOG.warn("Could not delete {} beside the library's snapshot", Database.FILE_NAME + log, e);
            }
        }
        boolean dirty = working.writeUntracked(c -> {
            boolean meanwhile = writing || working.writes() != begun;
            SyncState.set(c, SyncState.GENERATION, Long.toString(next));
            SyncState.set(c, SyncState.PUBLISHED_AT, now.toString());
            SyncState.set(c, SyncState.DIRTY, meanwhile ? "1" : "0");
            return meanwhile;
        });
        MarkerFile.write(library, new Marker(device.id(), device.name(), Marker.OPEN, since, now, next));
        LOG.info("Published generation {} of the library at {}", next, library);
        return new Published(next, dirty);
    }
}
