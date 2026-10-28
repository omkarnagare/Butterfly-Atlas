package com.butterflyatlas.app.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The config folder's {@code instance.json}: the running copy's port, process and key (design §4). */
class InstancePointerTest {

    @TempDir
    Path config;

    private InstancePointer pointer() {
        return new InstancePointer(config);
    }

    private static InstancePointer.Info mine(int port, String key) {
        return InstancePointer.Info.ofThisProcess(port, key);
    }

    @Test
    void writesAndReadsWhatASecondLaunchNeeds() {
        InstancePointer pointer = pointer();
        assertThat(pointer.read()).isEmpty();

        pointer.write(mine(7878, "abc_DEF-123"));

        InstancePointer.Info read = pointer.read().orElseThrow();
        assertThat(read.port()).isEqualTo(7878);
        assertThat(read.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(read.key()).isEqualTo("abc_DEF-123");
        assertThat(Files.exists(config.resolve("instance.json"))).isTrue();
    }

    @Test
    void theFileIsReadableByTheOwnerOnlyFromTheFirstByte() throws Exception {
        assumeTrue(config.getFileSystem().supportedFileAttributeViews().contains("posix"));

        pointer().write(mine(1, "k"));

        assertThat(Files.getPosixFilePermissions(config.resolve("instance.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.list(config).map(p -> p.getFileName().toString()).toList()).containsExactly("instance.json");
    }

    @Test
    void writingAgainReplacesTheOldPointer() {
        InstancePointer pointer = pointer();
        pointer.write(mine(1111, "one"));
        pointer.write(mine(2222, "two"));

        assertThat(pointer.read().orElseThrow().port()).isEqualTo(2222);
    }

    @Test
    void removeDeletesItOnlyWhenItIsOurs() {
        InstancePointer pointer = pointer();
        pointer.write(new InstancePointer.Info(4000, Long.MAX_VALUE - 1, 0, "someone-elses"));

        pointer.removeIfOurs("another-key");
        assertThat(pointer.read()).isPresent();
        pointer.removeIfOurs("someone-elses");

        assertThat(pointer.read()).isEmpty();
        assertThat(config.resolve("instance.json")).doesNotExist();
        pointer.removeIfOurs("x"); // nothing there: no error
    }

    @Test
    void aFileThatIsNotAPointerReadsAsNone() throws Exception {
        Files.writeString(config.resolve("instance.json"), "{not json");
        assertThat(pointer().read()).isEmpty();

        Files.writeString(config.resolve("instance.json"), "{\"port\": 0, \"pid\": 5, \"key\": \"\"}");
        assertThat(pointer().read()).isEmpty();
    }

    @Test
    void aProcessThatIsRunningIsNotStale() {
        assertThat(mine(1, "k").running()).isTrue();
    }

    @Test
    void aProcessThatIsGoneIsStale() throws Exception {
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        child.waitFor();
        InstancePointer.Info gone = new InstancePointer.Info(1, child.pid(), 0, "k");

        assertThat(gone.running()).isFalse();
    }

    @Test
    void aProcessNumberReusedByAnotherProgramIsStale() {
        long start = ProcessHandle.current().info().startInstant().map(i -> i.toEpochMilli()).orElseThrow();
        InstancePointer.Info reused = new InstancePointer.Info(1, ProcessHandle.current().pid(), start - 3_600_000, "k");

        assertThat(reused.running()).isFalse(); // same number, but it started an hour before this one did
        assertThat(new InstancePointer.Info(1, ProcessHandle.current().pid(), 0, "k").running()).isTrue(); // start unknown
    }

    @Test
    void anOldPointerIsReplacedNotRefused() {
        InstancePointer pointer = pointer();
        pointer.write(new InstancePointer.Info(4000, Long.MAX_VALUE - 1, 0, "dead"));
        Optional<InstancePointer.Info> old = pointer.read();
        assertThat(old).isPresent();
        assertThat(old.get().running()).isFalse();

        pointer.write(mine(5000, "alive"));

        assertThat(pointer.read().orElseThrow().key()).isEqualTo("alive");
    }

    @Test
    void aPointerOfAnotherLiveCopyIsNeverReplaced() {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        assumeTrue(parent != null && parent.isAlive());
        InstancePointer.Info theirs = new InstancePointer.Info(7878, parent.pid(), 0, "their-key");
        pointer().write(theirs);

        assertThatThrownBy(() -> pointer().write(mine(9999, "my-key"))).isInstanceOf(InstancePointer.AlreadyRunning.class);

        assertThat(pointer().read()).contains(theirs);
    }

    @Test
    void aPointerOfACopyThatIsGoneOrOfThisProcessIsReplaced() {
        pointer().write(new InstancePointer.Info(7878, Long.MAX_VALUE - 1, 0, "dead"));
        pointer().write(mine(1, "first"));
        pointer().write(mine(2, "second"));

        assertThat(pointer().read().orElseThrow().key()).isEqualTo("second");
    }
}
