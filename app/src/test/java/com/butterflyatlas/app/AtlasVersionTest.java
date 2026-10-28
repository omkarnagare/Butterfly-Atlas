package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtlasVersionTest {

    @Test
    void readsTheProjectVersionWrittenByTheBuild() {
        assertThat(AtlasVersion.current()).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
    }
}
