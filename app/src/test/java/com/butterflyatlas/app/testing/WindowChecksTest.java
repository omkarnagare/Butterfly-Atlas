package com.butterflyatlas.app.testing;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real-window check needs a browser: skipped without one locally, failed without one on CI (contract §5). */
class WindowChecksTest {

    @Test
    void theBrowserFoundIsUsed() {
        assertThat(WindowChecks.browser(Optional.of(Path.of("/chrome")), Map.of("CI", "true"))).isEqualTo(Path.of("/chrome"));
    }

    @Test
    void withoutABrowserTheCheckIsSkippedLocally() {
        assertThatThrownBy(() -> WindowChecks.browser(Optional.empty(), Map.of())).isInstanceOf(TestAbortedException.class);
        assertThatThrownBy(() -> WindowChecks.browser(Optional.empty(), Map.of("CI", "false")))
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void withoutABrowserOnCiTheCheckFailsSoCiNeverPassesBySkipping() {
        assertThatThrownBy(() -> WindowChecks.browser(Optional.empty(), Map.of("CI", "true")))
                .isInstanceOf(AssertionFailedError.class).hasMessageContaining("CI");
        assertThatThrownBy(() -> WindowChecks.browser(Optional.empty(), Map.of("CI", "TRUE")))
                .isInstanceOf(AssertionFailedError.class);
    }
}
