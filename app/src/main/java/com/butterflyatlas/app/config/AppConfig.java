package com.butterflyatlas.app.config;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the app remembers between launches: the library folder, and the chosen look ({@code theme}: {@link #LIGHT},
 * {@link #DARK}, or null to follow the computer; null is not written).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppConfig(String dataFolder, String theme) {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    /** A library folder with no saved look. */
    public AppConfig(String dataFolder) {
        this(dataFolder, null);
    }

    /** Whether {@code theme} is one the app knows. */
    public static boolean isTheme(String theme) {
        return LIGHT.equals(theme) || DARK.equals(theme);
    }
}
