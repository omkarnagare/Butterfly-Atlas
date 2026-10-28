package com.butterflyatlas.app.updates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a taxonomy update says about a failure that has no words of its own: never a class name (it means nothing to the person reading it). */
class UpdateWordsTest {

    private static final String PLAIN = "an unexpected problem; the details are in the log file";

    @Test
    void aFailureWithoutAMessageIsAnUnexpectedProblemNotAClassName() {
        assertThat(UpdateMoves.failed(new IllegalStateException()))
                .isEqualTo("The taxonomy update stopped: " + PLAIN + ". Choose Retry to try again.");
        assertThat(UpdateMoves.failed(new RuntimeException("  "))).doesNotContain("RuntimeException").contains(PLAIN);
        assertThat(UpdateMoves.failed(new RuntimeException("disk I/O error"))).contains("disk I/O error");
    }

    @Test
    void theDetailOfAFailureIsItsInnermostMessageOrTheSamePlainWords() {
        assertThat(Updates.detail(new RuntimeException("outer", new IllegalArgumentException("inner words"))))
                .isEqualTo("inner words");
        assertThat(Updates.detail(new RuntimeException(new java.nio.file.NoSuchFileException(null)))).isEqualTo(PLAIN);
        assertThat(Updates.detail(new IllegalStateException())).doesNotContain("IllegalState").isEqualTo(PLAIN);
    }
}
