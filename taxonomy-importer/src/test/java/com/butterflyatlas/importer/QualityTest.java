package com.butterflyatlas.importer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityTest {

    @Test
    void parsesLabelsCaseInsensitively() {
        assertThat(Quality.parse("research")).isEqualTo(Quality.RESEARCH);
        assertThat(Quality.parse("Verifiable")).isEqualTo(Quality.VERIFIABLE);
    }

    @Test
    void rejectsUnknownLabels() {
        assertThatThrownBy(() -> Quality.parse("casual"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quality must be 'research' or 'verifiable', got: casual");
    }

    @Test
    void onlyResearchGradeAddsTheQualityFilter() {
        assertThat(Quality.RESEARCH.queryParam()).isEqualTo("&quality_grade=research");
        assertThat(Quality.VERIFIABLE.queryParam()).isEmpty();
    }

    @Test
    void describesItselfForTheCliAndTheSourceNote() {
        assertThat(Quality.VERIFIABLE.description()).isEqualTo("all verifiable observations");
        assertThat(Quality.RESEARCH.description()).isEqualTo("research-grade observations only");
        assertThat(Quality.VERIFIABLE.noteWording()).isEqualTo("verifiable");
        assertThat(Quality.RESEARCH.noteWording()).isEqualTo("research-grade");
    }
}
