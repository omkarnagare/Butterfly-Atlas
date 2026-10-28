package com.butterflyatlas.app.imaging.heif;

import com.butterflyatlas.app.photos.PhotoProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoder runner against a fake decoder program (a separate Java process, {@link FakeDecoder}): working names,
 * timeouts and kills, output checks, cleanup, and the slots — all without any HEIC decoder.
 */
class DecoderRunTest {

    @TempDir
    Path dir;

    private Path folder() {
        return dir.resolve("Atlas").resolve(".thumbs").resolve(".decode");
    }

    private Path photo() throws IOException {
        Path photos = Files.createDirectories(dir.resolve("Atlas").resolve("images"));
        Path photo = photos.resolve("-rf ünïcode photo;$(touch x) 'q'.heic");
        Files.write(photo, new byte[] {1, 2, 3, 4});
        return photo;
    }

    private DecoderRun.Result run(String mode, int width, int height, int longSide, int storedWidth, int storedHeight)
            throws IOException {
        return new DecoderRun(folder(), FakeDecoder.command(mode, width, height), Duration.ofSeconds(20))
                .decode(photo(), longSide, storedWidth, storedHeight);
    }

    private List<Path> leftovers() throws IOException {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder())) {
            return files.toList();
        }
    }

    @Test
    void aGoodDecodeGivesThePictureAndLeavesNothingBehind() throws IOException {
        DecoderRun.Result result = run("ok", 64, 32, 960, 64, 32);

        assertThat(result.outcome()).as(result.why()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(result.image().getWidth()).isEqualTo(64);
        assertThat(result.image().getHeight()).isEqualTo(32);
        assertThat(result.swapped()).isFalse();
        assertThat(leftovers()).isEmpty();
        assertThat(Files.readAllBytes(photo())).as("the photo itself untouched").containsExactly(1, 2, 3, 4);
    }

    @Test
    void aTurnedOrScaledPictureMatchesAndAWrongSizeFails() throws IOException {
        DecoderRun.Result turned = run("ok", 32, 64, 960, 64, 32);
        assertThat(turned.outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(turned.swapped()).isTrue();

        assertThat(run("ok", 961, 720, 960, 4000, 3000).outcome()).as("within 2 px").isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(run("ok", 900, 720, 960, 4000, 3000).outcome()).isEqualTo(DecoderRun.Outcome.FAILED);
        assertThat(run("ok", 128, 64, 960, 64, 32).outcome()).as("never larger than the picture").isEqualTo(
                DecoderRun.Outcome.FAILED);
        DecoderRun.Result square = run("ok", 50, 50, 960, 50, 50);
        assertThat(square.outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(square.swapped()).isFalse();
    }

    @Test
    void aFullSizePictureIsDecodedSmallerForASmallLongSide() throws IOException {
        DecoderRun.Result result = run("ok", 4000, 3000, 960, 4000, 3000);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isBetween(960, 1920);
    }

    @Test
    void badOutputFailsEveryCheck() throws IOException {
        assertThat(run("garbage", 64, 32, 960, 64, 32).why()).isEqualTo("Not a JPEG");
        assertThat(run("huge", 64, 32, 960, 64, 32).why()).startsWith("The JPEG is larger than");
        assertThat(run("none", 64, 32, 960, 64, 32).why()).isEqualTo("No JPEG was written");
        for (String mode : List.of("garbage", "huge", "none")) {
            assertThat(run(mode, 64, 32, 960, 64, 32).outcome()).as(mode).isEqualTo(DecoderRun.Outcome.FAILED);
        }
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aCrashIsAProgramErrorWithItsOutputKept() throws IOException {
        DecoderRun.Result result = run("crash", 64, 32, 960, 64, 32);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.PROGRAM_ERROR);
        assertThat(result.output()).contains("fake decoder: cannot decode this");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aMissingProgramIsNoProgram() throws IOException {
        DecoderRun.Command missing = (in, out, side) -> List.of(dir.resolve("no-such-decoder").toString(), in.toString());

        DecoderRun.Result result = new DecoderRun(folder(), missing, Duration.ofSeconds(5)).decode(photo(), 960, 64, 32);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.NO_PROGRAM);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aHangingProgramIsKilledAtTheTimeout() throws IOException {
        long start = System.nanoTime();

        DecoderRun.Result result = new DecoderRun(folder(), FakeDecoder.command("hang", 64, 32), Duration.ofSeconds(2))
                .decode(photo(), 960, 64, 32);

        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.TIMED_OUT);
        assertThat(took).isBetween(1900L, 15_000L);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void everyProcessTheProgramStartedIsKilledToo() throws Exception {
        DecoderRun.Result result = new DecoderRun(folder(), FakeDecoder.command("child", 64, 32), Duration.ofSeconds(4))
                .decode(photo(), 960, 64, 32);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.TIMED_OUT);
        Matcher pid = Pattern.compile("child=(\\d+)").matcher(result.output());
        assertThat(pid.find()).as(result.output()).isTrue();
        ProcessHandle child = ProcessHandle.of(Long.parseLong(pid.group(1))).orElse(null);
        assertThat(child == null || !child.isAlive()).as("the child is gone").isTrue();
    }

    @Test
    void noUserFileNameReachesTheProgramAndItsInputIsTheFixedText() throws IOException {
        DecoderRun.Result result = run("echo", 64, 32, 960, 64, 32);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        Matcher args = Pattern.compile("args=echo\\|64\\|32\\|(.*)\\|(.*)\\|64").matcher(result.output());
        assertThat(args.find()).as(result.output()).isTrue();
        assertThat(Path.of(args.group(1)).getFileName().toString()).matches("[a-z][a-z0-9]{15}\\.heic");
        assertThat(Path.of(args.group(2)).getFileName().toString()).matches("[a-z][a-z0-9]{15}\\.jpg");
        assertThat(Path.of(args.group(1)).getParent()).isEqualTo(folder());
        assertThat(result.output()).doesNotContain("ünïcode", "photo;", "touch");
        assertThat(result.output()).contains("stdin=fixed script text");
    }

    @Test
    void theCommandsEnvironmentReachesTheProgram() throws IOException {
        DecoderRun.Command echo = FakeDecoder.command("echo", 64, 32);
        DecoderRun.Command withEnvironment = new DecoderRun.Command() {
            @Override
            public java.util.List<String> arguments(Path input, Path output, int longSide) {
                return echo.arguments(input, output, longSide);
            }

            @Override
            public java.util.Map<String, String> environment(Path input, Path output, int longSide) {
                return java.util.Map.of("ATLAS_IN", input.toString());
            }
        };

        DecoderRun.Result result = new DecoderRun(folder(), withEnvironment, Duration.ofSeconds(20)).decode(photo(), 960, 64, 32);

        assertThat(result.output()).containsPattern("env=.*[a-z0-9]{16}\\.heic");
    }

    @Test
    void extraFilesTheDecoderWritesAreDeletedToo() throws IOException {
        assertThat(run("extra", 64, 32, 960, 64, 32).outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void outputIsDrainedAndCapped() throws IOException {
        DecoderRun.Result result = run("loud", 64, 32, 960, 64, 32);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(result.output().length()).isEqualTo(DecoderRun.OUTPUT_CAP);
    }

    @Test
    void noWorkingCopyIsNotThePhotosFault() throws IOException {
        Files.createDirectories(folder().getParent());
        Files.write(folder(), new byte[0]); // a file where the folder should be

        DecoderRun.Result result = new DecoderRun(folder(), FakeDecoder.command("ok", 64, 32), Duration.ofSeconds(5))
                .decode(photo(), 960, 64, 32);

        assertThat(result.outcome()).isEqualTo(DecoderRun.Outcome.NO_WORKSPACE);
    }

    @Test
    void theFolderIsEmptiedAtStart() throws IOException {
        Files.createDirectories(folder());
        Files.write(folder().resolve("abcdefghijklmnop.heic"), new byte[] {1});
        Files.write(folder().resolve("abcdefghijklmnop.jpg"), new byte[] {1});

        DecoderRun.empty(folder());

        assertThat(leftovers()).isEmpty();
        DecoderRun.empty(dir.resolve("absent")); // nothing to do
    }

    /** A symbolic link; the test is skipped where links can't be made (Windows without Developer Mode or admin). */
    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links can't be made here: " + e.getMessage());
        }
    }

    @Test
    void aWorkingFolderThatIsALinkIsNeverFollowed() throws IOException {
        // fixes J4: .decode pointing elsewhere is deleted as a link and made again; what it pointed to is untouched
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path precious = Files.write(elsewhere.resolve("abcdefghijklmnop.heic"), new byte[] {7});
        Files.createDirectories(folder().getParent());
        link(folder(), elsewhere);

        DecoderRun.empty(folder());

        assertThat(precious).exists();
        assertThat(Files.isSymbolicLink(folder())).isFalse();
        assertThat(folder()).isDirectory();
        Files.delete(folder());
        link(folder(), elsewhere);
        assertThat(run("ok", 64, 32, 960, 64, 32).outcome()).isEqualTo(DecoderRun.Outcome.DONE);
        assertThat(precious).exists();
        try (var there = Files.list(elsewhere)) {
            assertThat(there.toList()).containsExactly(precious);
        }
    }

    @Test
    void aThumbsFolderThatIsALinkIsRefused() throws IOException {
        Path elsewhere = Files.createDirectories(dir.resolve("other-thumbs"));
        Files.createDirectories(folder().getParent().getParent());
        link(folder().getParent(), elsewhere);

        assertThat(run("ok", 64, 32, 960, 64, 32).outcome()).isEqualTo(DecoderRun.Outcome.NO_WORKSPACE);
        DecoderRun.empty(folder());
        try (var there = Files.list(elsewhere)) {
            assertThat(there.toList()).as("nothing made or emptied through the link").isEmpty();
        }
    }

    @Test
    void readOnlyIsFalseWhereThereIsNoSuchAttribute() throws IOException {
        // fixes J6 (Windows only): a read-only photo is copied, never linked; elsewhere there is no such mark
        assertThat(DecoderRun.readOnly(photo())).isEqualTo(System.getProperty("os.name").startsWith("Windows")
                && Boolean.TRUE.equals(Files.getAttribute(photo(), "dos:readonly")));
    }

    @Test
    void workingNamesAreRandomPlainAscii() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            names.add(DecoderRun.randomName());
        }
        assertThat(names).allMatch(n -> n.matches("[a-z][a-z0-9]{15}"));
        assertThat(names).doesNotHaveDuplicates();
    }

    // ---- slots ----

    @Test
    void atMostTwoAtOnceAndTheViewerGoesFirst() throws Exception {
        DecoderSlots slots = new DecoderSlots();
        assertThat(slots.acquire(PhotoProcessor.Use.CATCH_UP, Duration.ZERO)).isTrue();
        assertThat(slots.acquire(PhotoProcessor.Use.CATCH_UP, Duration.ZERO)).isTrue();
        assertThat(slots.acquire(PhotoProcessor.Use.VIEW, Duration.ofMillis(50))).as("both taken").isFalse();

        List<PhotoProcessor.Use> order = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> waiters = new ArrayList<>();
        for (PhotoProcessor.Use use : List.of(PhotoProcessor.Use.CATCH_UP, PhotoProcessor.Use.IMPORT,
                PhotoProcessor.Use.SHOWN, PhotoProcessor.Use.VIEW)) {
            waiters.add(CompletableFuture.runAsync(() -> {
                try {
                    if (slots.acquire(use, Duration.ofSeconds(10))) {
                        order.add(use);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (slots.waiting() < waiters.size() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        }
        for (int i = 0; i < 4; i++) {
            slots.release();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (order.size() < i + 1 && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        }
        CompletableFuture.allOf(waiters.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertThat(order).containsExactly(PhotoProcessor.Use.VIEW, PhotoProcessor.Use.SHOWN, PhotoProcessor.Use.IMPORT,
                PhotoProcessor.Use.CATCH_UP);
    }
}
