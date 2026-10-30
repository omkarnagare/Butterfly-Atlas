package com.butterflyatlas.app.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the app remembers between launches: the library folder, and the chosen look ({@code theme}: {@link #LIGHT},
 * {@link #DARK}, or null to follow the computer; null is not written). A change of library folder (change-library-folder
 * design §8) leaves three more, each written only when set, so an older app reads the file as before:
 * {@code previousDataFolder} (the library in use before the change), {@code changePending} (true from the switch until
 * the new library has opened once) and {@code oldCopy} (true after a move until the old copy is deleted or the user goes
 * back to it). A false flag is kept as none.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppConfig(String dataFolder, String theme, String previousDataFolder, Boolean changePending,
                        Boolean oldCopy) {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    public AppConfig {
        changePending = Boolean.TRUE.equals(changePending) ? Boolean.TRUE : null;
        oldCopy = Boolean.TRUE.equals(oldCopy) ? Boolean.TRUE : null;
    }

    /** A library folder with no saved look. */
    public AppConfig(String dataFolder) {
        this(dataFolder, null);
    }

    /** A library folder and a look, with no library change remembered. */
    public AppConfig(String dataFolder, String theme) {
        this(dataFolder, theme, null, null, null);
    }

    /** Whether {@code theme} is one the app knows. */
    public static boolean isTheme(String theme) {
        return LIGHT.equals(theme) || DARK.equals(theme);
    }

    /** Whether a change of library folder waits for the new library's first open. */
    @JsonIgnore
    public boolean pending() {
        return Boolean.TRUE.equals(changePending);
    }

    /** Whether a move left the old copy of the library at {@link #previousDataFolder()}. */
    @JsonIgnore
    public boolean hasOldCopy() {
        return Boolean.TRUE.equals(oldCopy);
    }

    /** The same settings with another look. */
    public AppConfig withTheme(String look) {
        return new AppConfig(dataFolder, look, previousDataFolder, changePending, oldCopy);
    }
}
