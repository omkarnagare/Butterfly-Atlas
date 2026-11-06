package com.butterflyatlas.app.desktop.mac;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The plain-Java parts of {@link MacNative}, on every OS. */
class MacNativeTest {

    @Test
    void makingOneLoadsNoNativeCode() {
        assertThat(new MacNative()).isNotNull();
    }

    @Test
    void activationBringsAllWindowsAndAsksAsMacOs13AndEarlierNeed() {
        assertThat(MacNative.ACTIVATE_ALL_WINDOWS | MacNative.ACTIVATE_IGNORING_OTHER_APPS).isEqualTo(3L);
    }
}
