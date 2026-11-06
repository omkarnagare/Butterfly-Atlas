package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the app hides its Dock tile on macOS (app-icon spec §4.1): the installed app ({@code jpackage.app-path} set)
 * has a tile with the butterfly; a development run (which would show a generic Java tile) and {@code -Datlas.noBrowser}
 * (no window of ours) keep {@code apple.awt.UIElement}.
 */
class MainDockTest {

    private final Map<String, String> properties = new HashMap<>();

    @Test
    void theInstalledAppShowsItsDockTile() {
        properties.put("jpackage.app-path", "/Applications/Butterfly Atlas.app/Contents/MacOS/Butterfly Atlas");

        assertThat(Main.hiddenFromDock(properties::get)).isFalse();
    }

    @Test
    void aDevelopmentRunHidesIt() {
        assertThat(Main.hiddenFromDock(properties::get)).isTrue();
        properties.put("jpackage.app-path", " ");
        assertThat(Main.hiddenFromDock(properties::get)).isTrue();
    }

    @Test
    void noBrowserHidesItEvenForTheInstalledApp() {
        properties.put("jpackage.app-path", "/Applications/Butterfly Atlas.app/Contents/MacOS/Butterfly Atlas");
        properties.put("atlas.noBrowser", "true");

        assertThat(Main.hiddenFromDock(properties::get)).isTrue();
    }
}
