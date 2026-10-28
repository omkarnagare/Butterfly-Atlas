package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The system properties and environment the app starts with, read and checked once. */
class AppOptionsTest {

    private static AppOptions of(Map<String, String> properties, Map<String, String> env) {
        return AppOptions.of(properties::get, env::get, Path.of("/config"));
    }

    @Test
    void defaultsAreTheFixedPortFirstTenSecondsAndSixty() {
        AppOptions options = of(Map.of(), Map.of());

        assertThat(options.port()).isNull();
        assertThat(options.noBrowser()).isFalse();
        assertThat(options.quitSeconds()).isEqualTo(10);
        assertThat(options.startSeconds()).isEqualTo(60);
        assertThat(options.dataDir()).isEmpty();
        assertThat(options.configDir()).isEqualTo(Path.of("/config"));
    }

    @Test
    void thePropertyWinsOverTheEnvironmentForThePort() {
        assertThat(of(Map.of("atlas.port", "7878"), Map.of("ATLAS_PORT", "9000")).port()).isEqualTo(7878);
        assertThat(of(Map.of(), Map.of("ATLAS_PORT", "9000")).port()).isEqualTo(9000);
        assertThat(of(Map.of("atlas.port", "0"), Map.of()).port()).isZero(); // any free port
    }

    @Test
    void readsTheOtherSettings() {
        AppOptions options = of(Map.of("atlas.noBrowser", "true", "atlas.quitSeconds", "1", "atlas.startSeconds", " 5 ",
                "atlas.dataDir", "/lib", "atlas.taxonomyDir", "/tax", "atlas.placesDir", "/places"), Map.of());

        assertThat(options.noBrowser()).isTrue();
        assertThat(options.quitSeconds()).isEqualTo(1);
        assertThat(options.startSeconds()).isEqualTo(5);
        assertThat(options.dataDir()).contains(Path.of("/lib"));
        assertThat(options.taxonomyDir()).contains(Path.of("/tax"));
        assertThat(options.placesDir()).contains(Path.of("/places"));
    }

    @Test
    void aBadSettingIsRefusedNamingIt() {
        assertThatThrownBy(() -> of(Map.of("atlas.quitSeconds", "0"), Map.of())).hasMessageContaining("atlas.quitSeconds");
        assertThatThrownBy(() -> of(Map.of("atlas.startSeconds", "soon"), Map.of())).hasMessageContaining("soon");
        assertThatThrownBy(() -> of(Map.of("atlas.port", "abc"), Map.of())).hasMessageContaining("atlas.port");
    }
}
