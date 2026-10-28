package com.butterflyatlas.app.window;

import com.butterflyatlas.app.OwnerOnly;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * The running copy's pointer file, {@code instance.json} in the config folder (design §4): its port, process and a key for
 * opening another window. Readable only by the user: POSIX {@code rw-------} from the first byte; on Windows the file's
 * access list is narrowed to its owner (as is everything in the config folder that could be read by another user of a shared
 * computer: the folder itself, {@code logs/} and {@code launch/}, see {@code AppDirs}). It is removed when a quit starts (audit 4); one a crash left behind is told by
 * its process being gone ({@link Info#running()}).
 */
public final class InstancePointer {

    static final String FILE = "instance.json";

    private static final Logger LOG = LoggerFactory.getLogger(InstancePointer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What a second launch needs. {@code startedAt} is the process's start (epoch milliseconds; 0 when the system does not
     * say), so a process number another program has since been given is not mistaken for the app.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(int port, long pid, long startedAt, String key) {

        /** This process, on {@code port}, opened with {@code key}. */
        public static Info ofThisProcess(int port, String key) {
            ProcessHandle me = ProcessHandle.current();
            return new Info(port, me.pid(), me.info().startInstant().map(Instant::toEpochMilli).orElse(0L), key);
        }

        /** Whether the process is still there (and is the one that wrote this, when both starts are known). */
        public boolean running() {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return false;
            }
            Optional<Instant> start = handle.get().info().startInstant();
            return startedAt == 0 || start.isEmpty() || Math.abs(start.get().toEpochMilli() - startedAt) <= 5_000;
        }
    }

    /** The pointer is another live copy's: this one must not replace it. */
    public static final class AlreadyRunning extends RuntimeException {

        AlreadyRunning(Path file) {
            super("Another copy of Butterfly Atlas is running (" + file + ")");
        }
    }

    private final Path file;

    public InstancePointer(Path configDir) {
        this.file = configDir.resolve(FILE);
    }

    /**
     * Writes the pointer so it is never seen half written or readable by others. One that is there is replaced when its
     * process is gone or is this one; one of another live copy is never replaced ({@link AlreadyRunning}).
     */
    public void write(Info info) {
        Optional<Info> there = read();
        if (there.isPresent() && there.get().pid() != info.pid() && there.get().running()) {
            throw new AlreadyRunning(file);
        }
        Path temp = file.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(temp);
            if (file.getFileSystem().supportedFileAttributeViews().contains(OwnerOnly.POSIX)) {
                FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OwnerOnly.FILE));
                Files.createFile(temp, ownerOnly);
            } else {
                Files.createFile(temp);
                narrowToOwner(temp);
            }
            Files.write(temp, JSON.writeValueAsBytes(info));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    /** The pointer, or empty when there is none or the file is not a pointer. */
    public Optional<Info> read() {
        try {
            Info info = JSON.readValue(Files.readAllBytes(file), Info.class);
            return info.port() > 0 && info.pid() > 0 && info.key() != null && !info.key().isBlank() ? Optional.of(info)
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Removes the pointer when it holds this key (a copy that did not start must not remove the running copy's). */
    public void removeIfOurs(String key) {
        Optional<Info> info = read();
        if (info.isPresent() && info.get().key().equals(key)) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warn("Could not remove {}", file, e);
            }
        }
    }

    /** Windows: the file's access list becomes its owner alone (the profile folder's own default is the user's anyway). */
    static void narrowToOwner(Path path) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view != null) {
                AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                view.setAcl(List.of(owner));
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not narrow the access to {} to its owner", path, e);
        }
    }
}
