package com.butterflyatlas.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MainPortTest {

    @Test
    void nullOrBlankMeansAnyFreePort() {
        assertThat(Main.parsePort("atlas.port", null)).isZero();
        assertThat(Main.parsePort("atlas.port", "")).isZero();
        assertThat(Main.parsePort("atlas.port", "   ")).isZero();
    }

    @Test
    void parsesPlainAndPaddedNumbers() {
        assertThat(Main.parsePort("atlas.port", "7878")).isEqualTo(7878);
        assertThat(Main.parsePort("atlas.port", " 7878 ")).isEqualTo(7878);
    }

    @Test
    void rejectsNonNumbersNamingTheSource() {
        assertThatThrownBy(() -> Main.parsePort("ATLAS_PORT", "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATLAS_PORT")
                .hasMessageContaining("abc");
    }

    @Test
    void rejectsOutOfRangeNumbers() {
        assertThatThrownBy(() -> Main.parsePort("atlas.port", "70000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Main.parsePort("atlas.port", "-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
