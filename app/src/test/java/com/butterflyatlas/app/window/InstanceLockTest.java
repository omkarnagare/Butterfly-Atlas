package com.butterflyatlas.app.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** One copy per library (design §4, audit 10): the library's lock file, held for the life of the process. */
class InstanceLockTest {

    /** Holds a library's lock in another program until its input closes: the other copy of the app. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            InstanceLock.Result result = InstanceLock.acquire(Path.of(args[0]));
            System.out.println(result.state());
            System.out.flush();
            System.in.read();
        }
    }

    @TempDir
    Path library;

    @Test
    void takesTheLockAndReleasesItOnClose() throws Exception {
        InstanceLock.Result first = InstanceLock.acquire(library);

        assertThat(first.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        assertThat(library.resolve(".atlas.lock")).exists();
        assertThat(InstanceLock.acquire(library).state()).isEqualTo(InstanceLock.State.HELD);

        first.lock().close();

        InstanceLock.Result again = InstanceLock.acquire(library);
        assertThat(again.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        again.lock().close();
    }

    @Test
    void acquireWithinWaitsForTheCopyThatIsQuitting() throws Exception {
        InstanceLock held = InstanceLock.acquire(library).lock();
        Thread quitting = new Thread(() -> {
            try {
                Thread.sleep(600);
                held.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        quitting.start();

        long began = System.nanoTime();
        InstanceLock.Result result = InstanceLock.acquireWithin(library, java.time.Duration.ofSeconds(10));

        assertThat(result.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        assertThat(System.nanoTime() - began).isGreaterThan(400_000_000L).isLessThan(5_000_000_000L);
        result.lock().close();
        quitting.join();
    }

    @Test
    void acquireWithinGivesUpAfterTheWaitAndReportsHeld() throws Exception {
        InstanceLock held = InstanceLock.acquire(library).lock();

        InstanceLock.Result result = InstanceLock.acquireWithin(library, java.time.Duration.ofMillis(300));

        assertThat(result.state()).isEqualTo(InstanceLock.State.HELD);
        held.close();
    }

    @Test
    void anotherProgramHoldingItIsHeldAndFreedWhenItQuits() throws Exception {
        Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), Holder.class.getName(), library.toString()).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(other.getInputStream()));
            assertThat(out.readLine()).isEqualTo("ACQUIRED");

            InstanceLock.Result mine = InstanceLock.acquire(library);

            assertThat(mine.state()).isEqualTo(InstanceLock.State.HELD);
            assertThat(mine.lock()).isNull();
        } finally {
            other.getOutputStream().close();
            assertThat(other.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        InstanceLock.Result later = InstanceLock.acquire(library);
        assertThat(later.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        later.lock().close();
    }

    @Test
    void aDriveThatCannotLockIsNotTheSameAsALockSomeoneHolds() {
        InstanceLock.Locker unsupported = channel -> {
            throw new IOException("No locks available");
        };

        InstanceLock.Result result = InstanceLock.acquire(library, unsupported);

        assertThat(result.state()).isEqualTo(InstanceLock.State.CANNOT_LOCK);
        assertThat(result.lock()).isNull();
        assertThat(result.reason()).contains("No locks available");
    }

    @Test
    void aLockFileThatCannotBeMadeIsAlsoCannotLock() throws Exception {
        Path notAFolder = Files.writeString(library.resolve("file"), "x");

        InstanceLock.Result result = InstanceLock.acquire(notAFolder);

        assertThat(result.state()).isEqualTo(InstanceLock.State.CANNOT_LOCK);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void theFileIsLeftBehindAndHarmless() throws Exception {
        InstanceLock.acquire(library).lock().close();

        assertThat(Files.size(library.resolve(".atlas.lock"))).isZero();
        try (FileChannel channel = FileChannel.open(library.resolve(".atlas.lock"), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            assertThat(lock).isNotNull();
        }
        assertThat(List.of(library.toFile().list())).contains(".atlas.lock");
    }

    @Test
    void aFileOfYourOwnChoosingIsLockedTheSameWayAndAMissingFolderCannotLock() throws Exception {
        Path file = library.resolve("instance.lock");

        InstanceLock.Result first = InstanceLock.acquireFile(file);
        assertThat(first.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        assertThat(InstanceLock.acquireFile(file).state()).isEqualTo(InstanceLock.State.HELD);
        first.lock().close();
        InstanceLock.Result again = InstanceLock.acquireFile(file);
        assertThat(again.state()).isEqualTo(InstanceLock.State.ACQUIRED);
        again.lock().close();

        assertThat(InstanceLock.acquireFile(library.resolve("no_such_folder").resolve("instance.lock")).state())
                .isEqualTo(InstanceLock.State.CANNOT_LOCK);
    }
}
