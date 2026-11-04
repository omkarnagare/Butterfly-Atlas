package com.butterflyatlas.app.sync;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.db.LibraryTooNewException;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One device at a time (one-device design): claiming a library before it opens (§4, §5, §7), with the live database in
 * this device's working copy and the library's {@code atlas.db} a snapshot.
 */
public final class LibrarySync {

    private static final Logger LOG = LoggerFactory.getLogger(LibrarySync.class);

    /** Opens the library on its working copy's database file. */
    @FunctionalInterface
    public interface Opener {
        Library open(Path databaseFile);
    }

    /** How many photos the discarded page names (§7). */
    static final int PHOTOS_NAMED = 20;

    private final Path configDir;
    private final Supplier<String> deviceName;
    private final Clock clock;
    private final MarkerFile.Pause pause;
    private volatile Device device;
    /** The library claimed in this run (null until one is). */
    private volatile Claim claimed;

    /** What this run claimed. */
    record Claim(Path folder, Library library, Instant since, boolean sent) {
    }

    /**
     * {@code configDir}: where {@code device-id} and the working copies are; {@code deviceName}: this device's name, read
     * once; {@code pause}: the wait before a marker that can't be read is read again.
     */
    public LibrarySync(Path configDir, Supplier<String> deviceName, Clock clock, MarkerFile.Pause pause) {
        this.configDir = configDir;
        this.deviceName = deviceName;
        this.clock = clock;
        this.pause = pause;
    }

    /** This device; made on first use. A device ID that can't be saved stops the claim with the setup screen's words. */
    public Device device() {
        Device known = device;
        if (known == null) {
            try {
                known = Device.load(configDir, deviceName);
            } catch (UncheckedIOException e) {
                LOG.warn("Could not save this device's ID", e);
                throw new Refusal.Failed("Butterfly Atlas couldn't save this device's ID in its settings folder "
                        + configDir + ". Check that the folder can be written, then try again.", e);
            }
            device = known;
        }
        return known;
    }

    /** The working copies' folder. */
    public Path workingCopies() {
        return configDir.resolve(WorkingCopy.FOLDER);
    }

    /**
     * Claims the library in {@code folder} (its lock already taken) and opens it with {@code opener} on this device's
     * working copy. {@code anyway}: Open here anyway was chosen (§4 case 3); {@code discard}: Continue was chosen on the
     * discarded page (§7). Throws a {@link Refusal} when it may not open here (another device has it, it hasn't synced, or
     * unsent changes would be discarded), {@link LibraryTooNewException} for a newer library, and another exception when a
     * step fails (the setup screen says why in plain words).
     */
    public synchronized Library claim(Path folder, boolean anyway, boolean discard, Opener opener) {
        Device me = device();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + folder, e);
        }
        Path snapshot = folder.resolve(Database.FILE_NAME);
        MarkerFile.Read read = MarkerFile.readSettled(folder, pause);
        if (read.unreadable()) {
            throw new UncheckedIOException(new IOException("The library's device marker couldn't be read: " + read.problem()));
        }
        Marker marker = read.marker();
        Instant now = clock.instant();
        ClaimRules.Access access = ClaimRules.access(marker, me.id(), now, anyway);
        if (access instanceof ClaimRules.TooSoon) {
            access = ClaimRules.access(marker, me.id(), now, false);
        }
        if (access instanceof ClaimRules.Refused refused) {
            throw new Refusal.OtherDevice(new Refusal.OtherDevice.Info(refused.name(), refused.seen().toString(),
                    refused.openAnywayFrom().toString()));
        }
        OptionalInt version = Database.newestSchemaVersion(snapshot);
        if (version.isPresent() && version.getAsInt() > Database.knownSchemaVersion()) {
            throw new LibraryTooNewException(version.getAsInt(), Database.knownSchemaVersion());
        }
        SyncState library;
        try {
            library = SyncState.readFile(snapshot);
        } catch (SQLException e) {
            throw new DatabaseException("Could not read the library's database " + snapshot, e);
        }
        long m = marker == null ? 0 : marker.generation();
        if (m > library.generation()) {
            throw new Refusal.NotSynced(new Refusal.NotSynced.Info(folder.toString()));
        }
        if (!library.converted()) {
            return convert(folder, snapshot, me, opener);
        }
        WorkingCopy working = WorkingCopy.of(configDir, library.libraryId());
        SyncState mine = null;
        if (working.exists()) {
            try {
                mine = working.state();
            } catch (SQLException e) {
                LOG.warn("This device's working copy of the library at {} can't be read; the library's copy is used", folder,
                        e);
            }
        }
        ClaimRules.Claim claim = ClaimRules.claim(m, library.generation(), mine == null ? null : mine.generation(),
                mine != null && mine.dirty());
        if (claim == ClaimRules.Claim.DISCARD) {
            if (!discard) {
                throw new Refusal.Discarded(discarded(folder, snapshot, working, marker, mine));
            }
            LOG.info("This device's unsent changes to the library at {} are discarded", folder);
            claim = ClaimRules.Claim.COPY_DOWN;
        }
        if (claim == ClaimRules.Claim.COPY_DOWN) {
            try {
                working.copyDown(snapshot);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (SQLException e) {
                throw new DatabaseException("Could not copy the library's database to this device", e);
            }
        }
        boolean unsent = claim == ClaimRules.Claim.USE_WORKING_COPY && mine.dirty();
        Library opened = opener.open(working.file());
        try {
            MarkerFile.write(folder, new Marker(me.id(), me.name(), Marker.OPEN, now, now, library.generation()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        working.claimed(now);
        boolean sent = false;
        if (SyncState.read(opened.database()).dirty()) {
            sent = publishAtClaim(folder, opened, me, now) && unsent;
        }
        claimed = new Claim(folder, opened, now, sent);
        LOG.info("This device claimed the library at {} (generation {}, {})", folder, library.generation(), claim);
        return opened;
    }

    /** A publish right after the claim (§5 case 2, or writes the opening made); a failure is logged, the copy stays dirty. */
    private boolean publishAtClaim(Path folder, Library opened, Device me, Instant now) {
        try {
            Publish.Result result = Publish.run(opened.database(), folder, me, now, clock.instant(), null, pause, () -> { });
            return result instanceof Publish.Published;
        } catch (IOException | SQLException | RuntimeException e) {
            LOG.warn("Could not publish the library at {} after claiming it", folder, e);
            return false;
        }
    }

    /**
     * Converting a library older than this version (§5): opened in place so V16 runs, given its ID and generation 1, copied
     * to the working copy, opened there, and published as generation 1 (one {@code atlas.db}, no {@code -wal} or
     * {@code -shm}); the publish writes the marker. A new library is made the same way.
     */
    private Library convert(Path folder, Path snapshot, Device me, Opener opener) {
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        Database inPlace = Database.open(snapshot);
        inPlace.writeUntracked(c -> {
            SyncState.set(c, SyncState.LIBRARY_ID, id);
            SyncState.set(c, SyncState.GENERATION, "1");
            SyncState.set(c, SyncState.DIRTY, "0");
            return null;
        });
        inPlace.read(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            return null;
        });
        WorkingCopy working = WorkingCopy.of(configDir, id);
        try {
            working.copyDown(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new DatabaseException("Could not copy the library's database to this device", e);
        }
        Library opened = opener.open(working.file());
        try {
            Publish.Result result = Publish.run(opened.database(), folder, me, now, clock.instant(), 1L, pause, () -> { });
            if (!(result instanceof Publish.Published)) {
                throw new IOException("The library's device marker names another device");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new DatabaseException("Could not publish the library's database", e);
        }
        working.claimed(now);
        claimed = new Claim(folder, opened, now, false);
        LOG.info("The library at {} was converted to one device at a time (library {})", folder, id);
        return opened;
    }

    /** The discarded page's facts (§7). */
    private Refusal.Discarded.Info discarded(Path folder, Path snapshot, WorkingCopy working, Marker marker,
                                             SyncState mine) {
        List<String> photos = new ArrayList<>();
        try {
            Set<String> known = new LinkedHashSet<>(photoFiles(snapshot, true));
            for (String rel : photoFiles(working.file(), false)) {
                if (!known.contains(rel) && Files.isRegularFile(folder.resolve(rel))) {
                    photos.add(rel);
                }
            }
        } catch (SQLException e) {
            LOG.warn("Could not list the photos the discarded changes held", e);
        }
        int more = Math.max(0, photos.size() - PHOTOS_NAMED);
        return new Refusal.Discarded.Info(marker == null ? null : marker.name(),
                marker == null ? null : marker.seen().toString(),
                mine.publishedAt() == null ? null : mine.publishedAt().toString(),
                List.copyOf(photos.subList(0, Math.min(PHOTOS_NAMED, photos.size()))), more);
    }

    /** The files of the live photos and those in Recently deleted, as a database names them. */
    private static List<String> photoFiles(Path database, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        if (readOnly) {
            config.setReadOnly(true);
        } else {
            config.setOpenMode(SQLiteOpenMode.READWRITE);
        }
        List<String> out = new ArrayList<>();
        try (Connection c = config.createConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM image WHERE deleted_at IS NULL"
                     + " UNION ALL SELECT deleted_rel_path FROM image WHERE deleted_rel_path IS NOT NULL ORDER BY 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Whether the claim of this run published unsent changes of the last session (§5 case 2). */
    public boolean sentLastSession() {
        Claim c = claimed;
        return c != null && c.sent();
    }

    /** The library this run claimed, if any. */
    Claim claimed() {
        return claimed;
    }
}
