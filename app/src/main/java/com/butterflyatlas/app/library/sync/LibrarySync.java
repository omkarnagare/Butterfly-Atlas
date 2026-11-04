package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.db.LibraryTooNewException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.SetupService;
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
import java.time.Duration;
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
    private final Ticker ticker;
    private volatile Device device;
    /** The library claimed in this run (null until one is). */
    private volatile Claim claimed;

    /** What this run claimed; {@code unsent}: the working copy had unsent changes of the last session (§5 case 2). */
    record Claim(Path folder, Library library, Instant since, boolean unsent) {
    }

    /**
     * How long after the first unsent write a publish follows (§5): once a minute while editing goes on, so the sync app
     * keeps few old copies and meets the snapshot in use less often; later writes don't restart the wait.
     */
    public static final Duration PUBLISH_EVERY = Duration.ofMinutes(1);
    /** How long after a failed publish the next try comes, and how many tries a round has (§5). */
    public static final Duration RETRY = Duration.ofMinutes(1);
    public static final int TRIES = 5;

    /** Publishing is trying again once a minute ({@code tries}: failed so far in this round, 1–4). */
    public static final String RETRYING = "retrying";
    /** 5 tries failed: Try again is offered. */
    public static final String STOPPED = "stopped";

    /**
     * What {@code GET /api/status} answers of one device at a time (one-device contract §2): publishing failing
     * ({@code publish}, null while it works), the library opened on another device ({@code openedElsewhere}, null
     * unless), and Home's one-time note ({@code syncNote}, null when none).
     */
    public record StatusPart(Publishing publish, OpenedElsewhere openedElsewhere, String syncNote) {
        public static final StatusPart NONE = new StatusPart(null, null, null);
    }

    /**
     * The library was opened on another device while open here (§6): its name (null: unknown), when it opened it (its
     * marker's {@code since}), and when this device was last in step when it has unsent changes (else null).
     */
    public record OpenedElsewhere(String name, String at, String unsentSince) {
    }

    /** The status's {@code publish} (one-device contract §2): null while publishing works. */
    public record Publishing(String state, String reason, int tries) {
    }

    /** Why a publish failed when the marker couldn't be read (§10). */
    static final String FOLDER_UNREADABLE = "Butterfly Atlas couldn't read your library folder. Check that it is there and"
            + " that your sync app is running, then try again.";

    /** Guards the publishing state below (held only briefly: a write's listener takes it). */
    private final Object publishing = new Object();
    /** Held while a publish runs: one at a time. */
    private final Object publishRun = new Object();
    private Ticker.Later debounce;
    private Ticker.Later retry;
    private int tries;
    private String reason;
    private boolean stopped;
    /** Whether a publish of this run sent the last session's unsent changes (§5 case 2; Home's note until dismissed). */
    private volatile boolean sent;
    /** The library was opened on another device while open here (§6); null unless. */
    private volatile OpenedElsewhere openedElsewhere;
    private Ticker.Later heartbeat;
    /** Stops the app's work, as a quit's first step does (set once the app's services exist). */
    private volatile Runnable stopWork = () -> { };
    /** Quits the app (set once it runs). */
    private volatile Runnable quitter = () -> LOG.warn("No app to quit");

    /** Whether Home's note "before you open your library on another device" waits to be dismissed (§8). */
    private volatile boolean beforeOtherDevice;
    /** Whether the working copies not claimed for 30 days were looked for in this run. */
    private boolean cleaned;

    /** Home's one-time notes (contract §2): the last session's changes were sent; quit before using another device. */
    public static final String SENT_LAST_SESSION = "sent_last_session";
    public static final String BEFORE_OTHER_DEVICE = "before_other_device";
    /** The {@code library_setting} key that records the second note dismissed on a device (its ID follows). */
    static final String NOTE_DISMISSED = "sync.before_other_device_dismissed.";
    /** Working copies whose library this device hasn't claimed for this long are deleted at start (§5 Disk space). */
    public static final Duration KEEP_UNCLAIMED = Duration.ofDays(30);

    /** How often the marker is read again and this device's {@code seen} written (§5 Heartbeat). */
    public static final Duration HEARTBEAT = Duration.ofMinutes(1);
    /** How long every window has to read that the app quits before it does (as a library change's quit). */
    public static final Duration QUIT_WAIT = Duration.ofMillis(1500);
    /** What every request but the status's and the quit's answers once the library was opened elsewhere (contract §3). */
    public static final String STOPPED_SAVING = "This device has stopped saving to your library.";

    /**
     * {@code configDir}: where {@code device-id} and the working copies are; {@code deviceName}: this device's name, read
     * once; {@code pause}: the wait before a marker that can't be read is read again.
     */
    public LibrarySync(Path configDir, Supplier<String> deviceName, Clock clock, MarkerFile.Pause pause) {
        this(configDir, deviceName, clock, pause, (task, delay) -> () -> { });
    }

    /** As above, with {@code ticker} running the publishes and tries that come later. */
    public LibrarySync(Path configDir, Supplier<String> deviceName, Clock clock, MarkerFile.Pause pause, Ticker ticker) {
        this.ticker = ticker;
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
        if (!cleaned) {
            cleaned = true;
            cleanUp();
        }
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
        claimed = new Claim(folder, opened, now, unsent);
        LOG.info("This device claimed the library at {} (generation {}, {})", folder, library.generation(), claim);
        return opened;
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

    /** Whether a publish of this run sent unsent changes of the last session (§5 case 2). */
    public boolean sentLastSession() {
        return sent;
    }

    /**
     * The claimed library has opened ({@code library}): its writes are published from now on (a minute after the first
     * unsent one),
     * and unsent changes (of the last session, or of the opening) are published at once. Nothing for another library.
     */
    public void start(Library library) {
        Claim c = claimed;
        if (c == null || c.library() != library) {
            return;
        }
        library.database().onWrite(this::written);
        try {
            String key = NOTE_DISMISSED + device().id();
            beforeOtherDevice = library.database().read(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM library_setting WHERE key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return !rs.next();
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not read whether Home's note about other devices was dismissed; it is shown", e);
            beforeOtherDevice = true;
        }
        synchronized (publishing) {
            heartbeat = ticker.schedule(this::beatLogged, HEARTBEAT);
        }
        boolean dirty;
        try {
            dirty = SyncState.read(library.database()).dirty();
        } catch (RuntimeException e) {
            LOG.warn("Could not read whether the library has unsent changes; it is published", e);
            dirty = true;
        }
        if (dirty) {
            boolean worked = attempt();
            sent = worked && c.unsent();
        }
    }

    /**
     * A write committed: a publish follows a minute later, unless one is already waiting (not while failed tries go on:
     * they publish it).
     */
    private void written() {
        synchronized (publishing) {
            if (!running() || tries > 0 || debounce != null) {
                return;
            }
            debounce = ticker.schedule(this::attemptLogged, PUBLISH_EVERY);
        }
    }

    /**
     * An import's filing, a taxonomy or place update, or a backup ended (§5): the library is published now when it has
     * unsent changes (not while failed tries go on).
     */
    public void workEnded() {
        Claim c = claimed;
        synchronized (publishing) {
            if (c == null || !running() || tries > 0) {
                return;
            }
        }
        try {
            if (SyncState.read(c.library().database()).dirty()) {
                ticker.schedule(this::attemptLogged, Duration.ZERO);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not read whether the library has unsent changes", e);
        }
    }

    /**
     * Publishes now when anything is unsent (a move of the library publishes first, one-device design §9); a failure counts
     * as a failed try, as any publish's does.
     */
    public void publishNow() {
        Claim c = claimed;
        if (c == null) {
            return;
        }
        try {
            if (SyncState.read(c.library().database()).dirty()) {
                attempt();
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not read whether the library has unsent changes", e);
        }
    }

    /** Whether this run has a library it may publish to. */
    private boolean running() {
        return claimed != null && !stopped;
    }

    private void attemptLogged() {
        try {
            attempt();
        } catch (RuntimeException | Error e) {
            LOG.error("Publishing the library stopped on an unexpected error", e);
        }
    }

    /**
     * One publish now; true when it worked. A failure counts as one of the round's 5 tries: another follows a minute later,
     * and after the 5th the publishing stops until Try again (§5). A write meanwhile leaves another publish to follow.
     */
    boolean attempt() {
        synchronized (publishRun) {
            Claim c = claimed;
            synchronized (publishing) {
                if (c == null || stopped) {
                    return false;
                }
                if (debounce != null) {
                    debounce.cancel();
                    debounce = null;
                }
                if (retry != null) {
                    retry.cancel();
                    retry = null;
                }
            }
            Publish.Result result;
            try {
                result = Publish.run(c.library().database(), c.folder(), device(), c.since(), clock.instant(), null, pause,
                        () -> { });
            } catch (IOException | SQLException | RuntimeException e) {
                failed(c.folder(), e);
                return false;
            }
            if (result instanceof Publish.NotOurs notOurs) {
                elsewhere(notOurs.marker());
                return false;
            }
            synchronized (publishing) {
                tries = 0;
                reason = null;
                if (((Publish.Published) result).dirty()) {
                    debounce = ticker.schedule(this::attemptLogged, PUBLISH_EVERY);
                }
            }
            return true;
        }
    }

    private void failed(Path folder, Exception e) {
        String why = e instanceof Publish.MarkerUnreadable ? FOLDER_UNREADABLE : SetupService.reasonFor(folder, e);
        synchronized (publishing) {
            tries++;
            reason = why;
            LOG.warn("Could not publish the library at {} (try {} of {})", folder, tries, TRIES, e);
            if (tries < TRIES) {
                retry = ticker.schedule(this::attemptLogged, RETRY);
            }
        }
    }

    /** Every minute (§5 Heartbeat): the marker is read again; then rewritten with a new {@code seen}. */
    private void beatLogged() {
        try {
            beat();
        } catch (RuntimeException | Error e) {
            LOG.error("The library's heartbeat stopped on an unexpected error", e);
        }
    }

    /**
     * Reads the marker again (§6): another device's means the library was opened elsewhere; this device's (or none, which
     * is written again) is rewritten with a new {@code seen}. One that can't be read twice in a row is left as it is
     * (publishing waits on it and says so). The next heartbeat follows a minute later.
     */
    void beat() {
        synchronized (publishRun) {
            Claim c = claimed;
            synchronized (publishing) {
                heartbeat = null;
                if (c == null || stopped) {
                    return;
                }
            }
            try {
                MarkerFile.Read read = MarkerFile.readSettled(c.folder(), pause);
                if (read.unreadable()) {
                    LOG.warn("The library's device marker couldn't be read ({}); it is read again in a minute", read.problem());
                } else if (read.marker() != null && !read.marker().names(device().id())) {
                    elsewhere(read.marker());
                    return;
                } else {
                    long generation = read.marker() != null ? read.marker().generation()
                            : SyncState.read(c.library().database()).generation();
                    Device me = device();
                    MarkerFile.write(c.folder(), new Marker(me.id(), me.name(), Marker.OPEN, c.since(), clock.instant(),
                            generation));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not write this device's heartbeat into the library's marker", e);
            }
            synchronized (publishing) {
                if (!stopped) {
                    heartbeat = ticker.schedule(this::beatLogged, HEARTBEAT);
                }
            }
        }
    }

    /**
     * The library's marker names another device (§6): this device stops all work, publishes nothing from now on, and every
     * window says what happened (with the time this device was last in step when it has unsent changes).
     */
    void elsewhere(Marker marker) {
        Claim c = claimed;
        synchronized (publishing) {
            if (stopped) {
                return;
            }
            stopped = true;
            for (Ticker.Later later : new Ticker.Later[] {debounce, retry, heartbeat}) {
                if (later != null) {
                    later.cancel();
                }
            }
            debounce = null;
            retry = null;
            heartbeat = null;
        }
        String unsent = null;
        try {
            SyncState mine = SyncState.read(c.library().database());
            if (mine.dirty() && mine.publishedAt() != null) {
                unsent = mine.publishedAt().toString();
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not read whether this device has unsent changes", e);
        }
        openedElsewhere = new OpenedElsewhere(marker.name(), marker.since().toString(), unsent);
        LOG.warn("The library was opened on another device ({}) at {}; this device has stopped saving to it",
                marker.name(), marker.since());
        try {
            stopWork.run();
        } catch (RuntimeException e) {
            LOG.warn("Could not stop all work after the library was opened elsewhere", e);
        }
    }

    /** What stops the app's work (a quit's first step); set once the services exist. */
    public void stopWorkWith(Runnable stopWork) {
        this.stopWork = stopWork;
    }

    /** What quits the app; set once it runs. */
    public void quitWith(Runnable quitter) {
        this.quitter = quitter;
    }

    /** Whether the library was opened on another device while open here: every request but a few is refused (§3). */
    public boolean openedElsewhere() {
        return openedElsewhere != null;
    }

    /**
     * Quit on the opened-elsewhere page (contract §2): the app quits after {@link #QUIT_WAIT}. 409
     * {@code not_opened_elsewhere} unless the library was opened elsewhere.
     */
    public void quit() {
        if (openedElsewhere == null) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.NOT_OPENED_ELSEWHERE,
                    "Your library wasn't opened on another device.");
        }
        LOG.info("Butterfly Atlas quits: the library was opened on another device");
        ticker.schedule(() -> quitter.run(), QUIT_WAIT);
    }

    /** The status's part, from memory. */
    public StatusPart status() {
        return new StatusPart(publishing(), openedElsewhere, syncNote());
    }

    /** Home's one-time note (contract §2): the last session's changes sent first, then the other; null when neither. */
    public String syncNote() {
        if (claimed == null || openedElsewhere != null) {
            return null;
        }
        return sent ? SENT_LAST_SESSION : beforeOtherDevice ? BEFORE_OTHER_DEVICE : null;
    }

    /**
     * Dismisses Home's current note (contract §2): {@code sent_last_session} in memory; {@code before_other_device} saved in
     * the library for this device. A save that fails throws, and the note stays.
     */
    public void dismissNote() {
        String note = syncNote();
        if (SENT_LAST_SESSION.equals(note)) {
            sent = false;
        } else if (BEFORE_OTHER_DEVICE.equals(note)) {
            String key = NOTE_DISMISSED + device().id();
            claimed.library().database().write(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO library_setting (key, value)"
                        + " VALUES (?, ?)")) {
                    ps.setString(1, key);
                    ps.setString(2, clock.instant().toString());
                    ps.executeUpdate();
                }
                return null;
            });
            beforeOtherDevice = false;
        }
    }

    /**
     * At quit (§8), once the app's work has stopped: a publish when anything is unsent, then the marker written as
     * {@code closed}. When either fails (the library's drive has gone), it is logged: the marker stays {@code open} for this
     * device and the working copy keeps its unsent changes, so the next start here sends them. Nothing once the library
     * was opened elsewhere (the marker is another device's).
     */
    public void close() {
        synchronized (publishRun) {
            Claim c = claimed;
            synchronized (publishing) {
                for (Ticker.Later later : new Ticker.Later[] {debounce, retry, heartbeat}) {
                    if (later != null) {
                        later.cancel();
                    }
                }
                debounce = null;
                retry = null;
                heartbeat = null;
                if (c == null || stopped) {
                    stopped = true;
                    return;
                }
                stopped = true;
            }
            try {
                Device me = device();
                SyncState mine = SyncState.read(c.library().database());
                long generation = mine.generation();
                if (mine.dirty()) {
                    Publish.Result result = Publish.run(c.library().database(), c.folder(), me, c.since(), clock.instant(),
                            null, pause, () -> { });
                    if (result instanceof Publish.NotOurs notOurs) {
                        LOG.warn("At quit, the library was open on another device ({}); nothing was sent",
                                notOurs.marker().name());
                        return;
                    }
                    generation = ((Publish.Published) result).generation();
                }
                MarkerFile.Read read = MarkerFile.readSettled(c.folder(), pause);
                if (read.marker() != null && !read.marker().names(me.id())) {
                    LOG.warn("At quit, the library's marker names another device ({}); it is left as it is",
                            read.marker().name());
                    return;
                }
                MarkerFile.write(c.folder(), new Marker(me.id(), me.name(), Marker.CLOSED, c.since(), clock.instant(),
                        generation));
                LOG.info("The library at {} is closed on this device (generation {})", c.folder(), generation);
            } catch (IOException | SQLException | RuntimeException e) {
                LOG.warn("Could not send the library's last changes or mark it closed at quit; the next start on this"
                        + " device sends them", e);
            }
        }
    }

    /**
     * Deletes the working copies of libraries this device hasn't claimed for 30 days (§5 Disk space), unless they hold
     * unsent changes (they wait for that library's claim, §7). What can't be read or deleted is logged and kept.
     */
    void cleanUp() {
        Path root = workingCopies();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant now = clock.instant();
        List<Path> folders;
        try (java.util.stream.Stream<Path> list = Files.list(root)) {
            folders = list.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            LOG.warn("Could not look for old working copies in {}", root, e);
            return;
        }
        for (Path folder : folders) {
            WorkingCopy copy = WorkingCopy.at(folder);
            Instant last = copy.lastClaimed();
            if (last == null || Duration.between(last, now).compareTo(KEEP_UNCLAIMED) < 0) {
                continue;
            }
            try {
                if (copy.exists() && copy.state().dirty()) {
                    LOG.info("The working copy {} holds unsent changes; it waits for its library to be opened", folder);
                    continue;
                }
            } catch (SQLException e) {
                LOG.warn("Could not read the working copy {}; it is kept", folder, e);
                continue;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(folder)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
                LOG.info("Deleted the working copy {}: its library hasn't been opened on this device for 30 days", folder);
            } catch (IOException e) {
                LOG.warn("Could not delete the old working copy {}", folder, e);
            }
        }
    }

    /** The status's {@code publish}: null while publishing works. */
    public Publishing publishing() {
        synchronized (publishing) {
            if (tries == 0 || stopped) {
                return null;
            }
            return new Publishing(tries >= TRIES ? STOPPED : RETRYING, reason, Math.min(tries, TRIES));
        }
    }

    /**
     * Try again (one-device contract §2): one attempt now; when it fails, another round of 5 tries starts (this one counts
     * as the first). 409 {@code not_stopped} unless publishing has stopped. Answers the status's {@code publish}.
     */
    public Publishing tryAgain() {
        synchronized (publishRun) {
            synchronized (publishing) {
                Publishing now = publishing();
                if (now == null || !STOPPED.equals(now.state())) {
                    throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.NOT_STOPPED,
                            "Butterfly Atlas is not waiting for you to try sending your changes again.");
                }
                tries = 0;
            }
            attempt();
            return publishing();
        }
    }

    /** The library this run claimed, if any. */
    Claim claimed() {
        return claimed;
    }
}
