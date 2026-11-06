package com.butterflyatlas.app.library.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Putting a finished {@code .part} in place of the snapshot or the marker (one-device design §5): Windows refuses to
 * replace a file another program has open, as a sync app does while it uploads it, so a refused rename is tried again
 * every half second, 6 times in all, before it counts as a failed publish.
 */
class ReplaceTest {

    @TempDir
    Path folder;

    private final List<Long> pauses = new ArrayList<>();
    private final MarkerFile.Pause pause = pauses::add;

    private Path part() throws IOException {
        return Files.writeString(folder.resolve("atlas.db.part"), "new");
    }

    @Test
    void aRenameTheSyncAppHoldsUpIsTriedAgainUntilItWorks() throws IOException {
        Path part = part();
        Path target = Files.writeString(folder.resolve("atlas.db"), "old");
        AtomicInteger calls = new AtomicInteger();
        Replace.Mover refusedTwice = (from, to) -> {
            if (calls.incrementAndGet() <= 2) {
                throw new FileSystemException(to.toString(), null,
                        "The process cannot access the file because it is being used by another process");
            }
            Replace.Mover.REAL.move(from, to);
        };

        Replace.replace(part, target, refusedTwice, pause);

        assertThat(target).hasContent("new");
        assertThat(part).doesNotExist();
        assertThat(calls).hasValue(3);
        assertThat(pauses).containsExactly(500L, 500L);
    }

    @Test
    void aRenameRefusedEveryTimeFailsAfter6TriesWithTheLastReason() throws IOException {
        Path part = part();
        Path target = Files.writeString(folder.resolve("atlas.db"), "old");
        AtomicInteger calls = new AtomicInteger();
        Replace.Mover alwaysRefused = (from, to) -> {
            calls.incrementAndGet();
            throw new AccessDeniedException(to.toString());
        };

        assertThatThrownBy(() -> Replace.replace(part, target, alwaysRefused, pause))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(calls).hasValue(6);
        assertThat(pauses).hasSize(5).containsOnly(500L);
        assertThat(target).hasContent("old");
    }

    @Test
    void aMissingPartIsNotTriedAgain() {
        Path target = folder.resolve("atlas.db");
        AtomicInteger calls = new AtomicInteger();
        Replace.Mover missing = (from, to) -> {
            calls.incrementAndGet();
            throw new NoSuchFileException(from.toString());
        };

        assertThatThrownBy(() -> Replace.replace(folder.resolve("atlas.db.part"), target, missing, pause))
                .isInstanceOf(NoSuchFileException.class);
        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void aRenameThatWorksAtOnceDoesNotWait() throws IOException {
        Path part = part();
        Path target = folder.resolve(MarkerFile.NAME);

        Replace.replace(part, target, Replace.Mover.REAL, pause);

        assertThat(target).hasContent("new");
        assertThat(pauses).isEmpty();
    }
}
