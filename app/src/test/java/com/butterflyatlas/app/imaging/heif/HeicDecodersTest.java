package com.butterflyatlas.app.imaging.heif;

import com.butterflyatlas.app.photos.HeicDecodeException;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.testing.TestHeif;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding and checking the decoder, the status and its changes, and decodes through it — with fake decoder programs
 * whose answer to each probe (and to a photo) the test chooses.
 */
class HeicDecodersTest {

    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    @AfterEach
    void reset() {
        HeicStatus.publish(HeicStatus.checking("mac"));
    }

    private Path folder() {
        return dir.resolve(".thumbs").resolve(".decode");
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = HeicDecoders.class.getResourceAsStream("/heif/" + name)) {
            return in.readAllBytes();
        }
    }

    /**
     * A fake program: the rot probe answered by {@code rot.get()}, the mirror probe by {@code mirror.get()}, a photo by
     * {@code photo.get()} (a mode writing a {@code width × height} picture), each chosen when it starts.
     */
    private HeicDecoders.Program fake(Supplier<String> rot, Supplier<String> mirror, Supplier<String> photo, int width,
                                      int height, boolean wic, String say) throws IOException {
        byte[] rotBytes = resource("probe-rot.heic");
        byte[] mirrorBytes = resource("probe-mirror.heic");
        DecoderRun.Command command = (in, out, side) -> {
            String mode;
            try {
                byte[] bytes = Files.readAllBytes(in);
                mode = Arrays.equals(bytes, rotBytes) ? rot.get() : Arrays.equals(bytes, mirrorBytes) ? mirror.get()
                        : photo.get();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            List<String> args = new ArrayList<>(FakeDecoder.command(mode, width, height).arguments(in, out, side));
            args.add(say);
            return args;
        };
        return new HeicDecoders.Program("fake", Path.of(FakeDecoder.javaPath()), command, wic);
    }

    private HeicDecoders decoders(HeicDecoders.Program program) {
        return decoders(() -> Optional.ofNullable(program), Duration.ofSeconds(20));
    }

    private HeicDecoders decoders(Supplier<Optional<HeicDecoders.Program>> finder, Duration probeTime) {
        return new HeicDecoders("mac", finder, this::folder, Duration.ofSeconds(20), probeTime, probeTime, clock::get);
    }

    private static HeifInfo info(byte[] heif, Path dir) throws IOException {
        Path file = dir.resolve("info.heic");
        Files.write(file, heif);
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return HeifBoxes.read(channel, channel.size());
        }
    }

    private Path photo(byte[] heif) throws IOException {
        Path file = Files.createDirectories(dir.resolve("images")).resolve("IMG 0042.HEIC");
        Files.write(file, heif);
        return file;
    }

    @Test
    void aDecoderThatLeavesPicturesAsStoredIsAvailableAndTheAppTurns() throws Exception {
        HeicDecoders decoders = decoders(fake(() -> "rot-stored", () -> "rot-stored", () -> "ok", 640, 480, false, ""));
        assertThat(decoders.status()).isEqualTo(HeicStatus.checking("mac"));

        HeicStatus found = decoders.check().get(30, TimeUnit.SECONDS);

        assertThat(found).isEqualTo(HeicStatus.available("fake", "mac"));
        assertThat(HeicStatus.current()).isEqualTo(found);
        byte[] heif = TestHeif.still(640, 480).turn(1).build();
        HeicDecoders.Decoded decoded = decoders.decode(photo(heif), 960, info(heif, dir), PhotoProcessor.Use.VIEW);
        assertThat(decoded.image().getWidth()).isEqualTo(640);
        assertThat(decoded.orientation()).as("the turn is the app's to apply").isEqualTo(8);
        try (var left = Files.list(folder())) {
            assertThat(left.toList()).as("probes and decodes leave nothing").isEmpty();
        }
    }

    @Test
    void aDecoderThatTurnsAndMirrorsLeavesNothingToApply() throws Exception {
        HeicDecoders decoders = decoders(fake(() -> "rot-applied", () -> "mirror-applied", () -> "ok", 480, 640, false, ""));

        assertThat(decoders.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();

        byte[] heif = TestHeif.still(640, 480).turn(1).mirror(1).build();
        HeicDecoders.Decoded decoded = decoders.decode(photo(heif), 960, info(heif, dir), PhotoProcessor.Use.SHOWN);
        assertThat(decoded.orientation()).isEqualTo(1);
        // the same decoder answering a sideways photo unturned contradicts its check: the photo fails
        byte[] upright = TestHeif.still(480, 640).turn(1).build();
        assertThatThrownBy(() -> decoders.decode(photo(upright), 960, info(upright, dir), PhotoProcessor.Use.SHOWN))
                .isInstanceOf(IOException.class).isNotInstanceOf(HeicDecodeException.class)
                .hasMessageContaining("against what its check found");
    }

    @Test
    void aProbeAnsweredWronglyMeansMissing() throws Exception {
        HeicDecoders decoders = decoders(fake(() -> "white-br", () -> "rot-stored", () -> "ok", 64, 32, false, ""));

        assertThat(decoders.check().get(30, TimeUnit.SECONDS)).isEqualTo(HeicStatus.missing("mac", "failed"));
    }

    @Test
    void noProgramIsAbsent() throws Exception {
        HeicDecoders decoders = decoders(() -> Optional.empty(), Duration.ofSeconds(5));

        assertThat(decoders.check().get(10, TimeUnit.SECONDS)).isEqualTo(HeicStatus.missing("mac", "absent"));
        assertThatThrownBy(() -> decoders.decode(dir.resolve("x.heic"), 960, null, PhotoProcessor.Use.VIEW))
                .isInstanceOfSatisfying(HeicDecodeException.class,
                        e -> assertThat(e.kind()).isEqualTo(HeicDecodeException.Kind.UNAVAILABLE));
    }

    @Test
    void windowsReasonsComeFromWhatTheScriptSaid() throws Exception {
        assertThat(decoders(fake(() -> "say", () -> "say", () -> "ok", 64, 32, true, "")).check().get(30, TimeUnit.SECONDS)
                .reason()).as("PowerShell stopped before the script began").isEqualTo("blocked");
        assertThat(decoders(fake(() -> "say", () -> "say", () -> "ok", 64, 32, true, "ATLAS-READY ATLAS-ERROR 88982F50"))
                .check().get(30, TimeUnit.SECONDS).reason()).isEqualTo("no_heif");
        assertThat(decoders(fake(() -> "say", () -> "say", () -> "ok", 64, 32, true, "ATLAS-READY ATLAS-ERROR C00D5212"))
                .check().get(30, TimeUnit.SECONDS).reason()).isEqualTo("failed");
    }

    @Test
    void aCheckThatTimesOutIsTriedOnceMoreThenMissing() throws Exception {
        List<HeicStatus> seen = new CopyOnWriteArrayList<>();
        HeicDecoders decoders = new HeicDecoders("mac",
                () -> {
                    try {
                        return Optional.of(fake(() -> "hang", () -> "hang", () -> "ok", 64, 32, false, ""));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, this::folder, Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(2), System::nanoTime);
        decoders.onChange(seen::add);
        long start = System.nanoTime();

        HeicStatus status = decoders.check().get(30, TimeUnit.SECONDS);

        assertThat(status).isEqualTo(HeicStatus.missing("mac", "timed_out"));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(2900);
        assertThat(seen).as("checking was the state before").containsExactly(HeicStatus.missing("mac", "timed_out"));
    }

    @Test
    void aRunningCheckIsJoinedAndCheckAndWaitAnswersTheStateAfterItsWait() throws Exception {
        HeicDecoders decoders = decoders(fake(() -> "slow", () -> "slow", () -> "ok", 64, 32, false, ""));

        CompletableFuture<HeicStatus> first = decoders.check();
        CompletableFuture<HeicStatus> second = decoders.check();
        HeicStatus soon = decoders.checkAndWait(Duration.ofMillis(100));

        assertThat(second).isSameAs(first);
        assertThat(soon.isChecking()).isTrue();
        assertThat(first.get(30, TimeUnit.SECONDS).reason()).as("slow's 64 x 32 answer is 'stored' for both").isNull();
    }

    @Test
    void aProgramErrorOnAPhotoChecksAgainAndBlamesThePhotoOnlyWhenTheProbesStillPass() throws Exception {
        AtomicReference<String> probes = new AtomicReference<>("rot-stored");
        HeicDecoders decoders = decoders(fake(probes::get, probes::get, () -> "crash", 64, 32, false, ""));
        assertThat(decoders.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
        byte[] heif = TestHeif.still(64, 32).build();
        HeifInfo info = info(heif, dir);
        Path photo = photo(heif);

        clock.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThatThrownBy(() -> decoders.decode(photo, 960, info, PhotoProcessor.Use.VIEW))
                .as("probes pass: the photo is at fault").isInstanceOf(IOException.class)
                .isNotInstanceOf(HeicDecodeException.class);

        probes.set("crash");
        assertThatThrownBy(() -> decoders.decode(photo, 960, info, PhotoProcessor.Use.VIEW))
                .as("checked within the last minute: not again").isNotInstanceOf(HeicDecodeException.class);
        assertThat(decoders.status().isAvailable()).isTrue();

        clock.addAndGet(Duration.ofMinutes(2).toNanos());
        java.util.Set<String> states = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread watcher = Thread.ofVirtual().start(() -> {
            while (watching.get()) {
                states.add(HeicStatus.current().state());
            }
        });
        assertThatThrownBy(() -> decoders.decode(photo, 960, info, PhotoProcessor.Use.VIEW))
                .isInstanceOfSatisfying(HeicDecodeException.class,
                        e -> assertThat(e.kind()).isEqualTo(HeicDecodeException.Kind.UNAVAILABLE));
        assertThat(decoders.status()).isEqualTo(HeicStatus.missing("mac", "failed"));
        watching.set(false);
        watcher.join();
        assertThat(states).as("the re-check never shows checking (fixes J2)").doesNotContain("checking");
    }

    @Test
    void aProgramGoneMidSessionMarksTheDecoderMissing() throws Exception {
        // a stand-in program that can be taken away: a script that starts the fake (tests only; the app never uses a shell)
        Path program = dir.resolve("decoder");
        Files.writeString(program, "#!/bin/sh\nexec '" + FakeDecoder.javaPath() + "' \"$@\"\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File.separatorChar == '/' && program.toFile().setExecutable(true),
                "needs a POSIX shell");
        DecoderRun.Command command = (in, out, side) -> List.of(program.toString(), "-Djava.awt.headless=true", "-cp",
                FakeDecoder.classPath(),
                FakeDecoder.class.getName(), "rot-stored", "64", "32", in.toString(), out.toString(), "64");
        HeicDecoders decoders = decoders(new HeicDecoders.Program("fake", program, command, false));
        HeicStatus first = decoders.check().get(30, TimeUnit.SECONDS);
        Files.delete(program);
        byte[] heif = TestHeif.still(64, 32).build();

        assertThatThrownBy(() -> decoders.decode(photo(heif), 960, info(heif, dir), PhotoProcessor.Use.VIEW))
                .isInstanceOfSatisfying(HeicDecodeException.class,
                        e -> assertThat(e.kind()).isEqualTo(HeicDecodeException.Kind.UNAVAILABLE));
        assertThat(decoders.status()).as("after " + first).isEqualTo(HeicStatus.missing("mac", "absent"));
    }

    @Test
    void aTimedOutDecodeIsNotThePhotosFault() throws Exception {
        HeicDecoders decoders = new HeicDecoders("mac", () -> {
            try {
                return Optional.of(fake(() -> "rot-stored", () -> "rot-stored", () -> "hang", 64, 32, false, ""));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, this::folder, Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(20), System::nanoTime);
        assertThat(decoders.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
        byte[] heif = TestHeif.still(64, 32).build();

        assertThatThrownBy(() -> decoders.decode(photo(heif), 960, info(heif, dir), PhotoProcessor.Use.CATCH_UP))
                .isInstanceOfSatisfying(HeicDecodeException.class,
                        e -> assertThat(e.kind()).isEqualTo(HeicDecodeException.Kind.TIMED_OUT));
    }

    @Test
    void aPictureLargerThanThePixelLimitIsNeverSentToTheDecoder() throws Exception {
        HeicDecoders decoders = new HeicDecoders("mac", () -> {
            try {
                return Optional.of(fake(() -> "rot-stored", () -> "rot-stored", () -> "hang", 64, 32, false, ""));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, this::folder, Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(20), System::nanoTime);
        assertThat(decoders.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
        byte[] heif = TestHeif.still(20_000, 20_000).grid(40, 40, "hvc1").build(); // 400 megapixels: fixes J8
        long start = System.nanoTime();

        assertThatThrownBy(() -> decoders.decode(photo(heif), 960, info(heif, dir), PhotoProcessor.Use.VIEW))
                .isInstanceOf(IOException.class).isNotInstanceOf(HeicDecodeException.class)
                .hasMessageContaining("pixel");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).as("not started").isLessThan(1500);
    }

    /** A path as this OS writes it (a command is built from paths: {@code \l\a.heic} on Windows). */
    private static String os(String path) {
        return Path.of(path).toString();
    }

    @Test
    void theDecoderIsFoundAtItsFixedPathOrForced() {
        assertThat(HeicDecoders.find("mac", "none")).isEmpty();
        assertThat(HeicDecoders.find("other", null)).isEmpty();
        if (Files.isRegularFile(Path.of("/usr/bin/sips"))) {
            assertThat(HeicDecoders.find("mac", null)).get().extracting(HeicDecoders.Program::path)
                    .isEqualTo(Path.of("/usr/bin/sips"));
            assertThat(HeicDecoders.find("linux", "sips")).isPresent();
        }
        assertThat(HeicDecoders.sips(Path.of("/usr/bin/sips")).command().arguments(Path.of("/l/a.heic"),
                Path.of("/l/b.jpg"), 960)).containsExactly(os("/usr/bin/sips"), "-s", "format", "jpeg", "-s", "formatOptions",
                "90", "-Z", "960", os("/l/a.heic"), "--out", os("/l/b.jpg"));
        assertThat(HeicDecoders.heifConvert(Path.of("/usr/bin/heif-convert")).command().arguments(Path.of("/l/a.heic"),
                Path.of("/l/b.jpg"), 960)).containsExactly(os("/usr/bin/heif-convert"), "-q", "90", os("/l/a.heic"),
                os("/l/b.jpg"));
        assertThat(WicScript.powershell("C:\\Windows").toString()).endsWith("powershell.exe");
    }

    @Test
    void theWindowsScriptIsFixedTextAndThePathsComeThroughTheEnvironment() {
        // fixes J3, J7: no path in the script or the arguments, whatever the library's path holds
        Path in = Path.of("C:\\Users\\Ann O'Brien\\Bilder Ünïcode\\Atlas\\.thumbs\\.decode\\abc.heic");
        Path out = Path.of("C:\\Users\\Ann O'Brien\\Bilder Ünïcode\\Atlas\\.thumbs\\.decode\\abc.jpg");
        DecoderRun.Command command = HeicDecoders.wic(Path.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
                .command();

        assertThat(command.input(in, out, 960)).isEqualTo(WicScript.SCRIPT).doesNotContain("O'Brien", "abc");
        assertThat(command.arguments(in, out, 960)).noneMatch(a -> a.contains("abc"));
        assertThat(command.environment(in, out, 960)).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "ATLAS_IN", in.toString(), "ATLAS_OUT", out.toString(), "ATLAS_SIDE", "960"));
        assertThat(WicScript.SCRIPT).contains("$in = $env:ATLAS_IN", "'ReadWrite, Delete'", "OnLoad", "exit 0", "exit 4",
                "exit 5").endsWith("\r\n");
        assertThat(WicScript.SCRIPT.indexOf(WicScript.READY)).as("the marker before WPF is loaded (fixes J10)")
                .isLessThan(WicScript.SCRIPT.indexOf("Add-Type"));
        assertThat(WicScript.reason("ATLAS-READY\nATLAS-ERROR 80004005")).as("WPF not loading is failed").isEqualTo("failed");
        assertThat(WicScript.arguments(Path.of("ps.exe"))).containsExactly("ps.exe", "-NoProfile", "-NonInteractive",
                "-Command", "-");
    }

    @Test
    void aWorkingFolderThatCannotBeUsedIsNeverTheDecoderMissing() throws Exception {
        AtomicReference<Path> folder = new AtomicReference<>(folder());
        HeicDecoders decoders = new HeicDecoders("mac", () -> {
            try {
                return Optional.of(fake(() -> "rot-stored", () -> "rot-stored", () -> "ok", 64, 32, false, ""));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, folder::get, Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofSeconds(20), System::nanoTime);
        assertThat(decoders.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
        Path blocked = Files.write(dir.resolve("not-a-folder"), new byte[] {1});
        folder.set(blocked.resolve(".decode"));

        assertThat(decoders.check().get(30, TimeUnit.SECONDS)).as("it worked before: it still does (fixes J3)")
                .isEqualTo(HeicStatus.available("fake", "mac"));

        HeicDecoders fresh = new HeicDecoders("mac", () -> Optional.empty(), folder::get, Duration.ofSeconds(20),
                Duration.ofSeconds(20), Duration.ofSeconds(20), System::nanoTime);
        assertThat(fresh.check().get(30, TimeUnit.SECONDS)).isEqualTo(HeicStatus.missing("mac", "absent"));
        HeicDecoders never = new HeicDecoders("windows", () -> {
            try {
                return Optional.of(fake(() -> "rot-stored", () -> "rot-stored", () -> "ok", 64, 32, false, ""));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, folder::get, Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofSeconds(20), System::nanoTime);
        HeicStatus status = never.check().get(30, TimeUnit.SECONDS);
        assertThat(status).isEqualTo(HeicStatus.missing("windows", "workspace"));
        assertThat(status.missingNote()).isEqualTo(HeicStatus.WORKSPACE_NOTE);
    }

    @Test
    void listenersHearOnlyASettledChange() throws Exception {
        List<HeicStatus> seen = new CopyOnWriteArrayList<>();
        AtomicReference<String> probes = new AtomicReference<>("rot-stored");
        HeicDecoders decoders = decoders(fake(probes::get, probes::get, () -> "ok", 64, 32, false, ""));
        decoders.onChange(seen::add);

        decoders.check().get(30, TimeUnit.SECONDS);
        decoders.check().get(30, TimeUnit.SECONDS); // the same decoder again: nothing to queue again (fixes J2)
        probes.set("white-br");
        decoders.check().get(30, TimeUnit.SECONDS);
        probes.set("rot-stored");
        decoders.check().get(30, TimeUnit.SECONDS);

        assertThat(seen).containsExactly(HeicStatus.available("fake", "mac"), HeicStatus.missing("mac", "failed"),
                HeicStatus.available("fake", "mac"));
    }
}
