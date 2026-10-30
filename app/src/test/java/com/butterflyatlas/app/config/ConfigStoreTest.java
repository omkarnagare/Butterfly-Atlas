package com.butterflyatlas.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {

    @TempDir
    Path dir;

    @Test
    void isEmptyWhenThereIsNoFile() {
        assertThat(new ConfigStore(dir.resolve("config")).load()).isEmpty();
    }

    @Test
    void savesAndLoadsTheDataFolder() {
        ConfigStore store = new ConfigStore(dir.resolve("not-yet").resolve("config"));

        store.save(new AppConfig("/Users/me/Documents/Butterfly Atlas"));

        assertThat(store.file()).exists();
        assertThat(new ConfigStore(dir.resolve("not-yet").resolve("config")).load())
                .contains(new AppConfig("/Users/me/Documents/Butterfly Atlas"));
    }

    @Test
    void ignoresADamagedFile() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.file(), "{ not json");

        assertThat(store.load()).isEmpty();
    }

    @Test
    void ignoresABlankDataFolder() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.file(), "{\"dataFolder\": \"  \"}");

        assertThat(store.load()).isEmpty();
    }

    @Test
    void replacesTheFileAndLeavesNoTemporaryFile() {
        ConfigStore store = new ConfigStore(dir);
        store.save(new AppConfig("/a"));

        store.save(new AppConfig("/b"));

        assertThat(store.load()).contains(new AppConfig("/b"));
        assertThat(dir.resolve(ConfigStore.FILE_NAME + ".tmp")).doesNotExist();
    }

    @Test
    void savesAndLoadsTheTheme() {
        ConfigStore store = new ConfigStore(dir);

        store.save(new AppConfig("/a", "dark"));

        assertThat(store.load()).contains(new AppConfig("/a", "dark"));
        assertThat(store.file()).content().contains("\"theme\" : \"dark\"");
    }

    @Test
    void writesNoThemeKeyWhenThereIsNoSavedChoice() throws Exception {
        ConfigStore store = new ConfigStore(dir);

        store.save(new AppConfig("/a"));

        assertThat(Files.readString(store.file())).doesNotContain("theme");
        assertThat(store.load()).contains(new AppConfig("/a", null));
    }

    @Test
    void readsAMissingOrNullThemeAsNoChoice() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.file(), "{\"dataFolder\": \"/a\", \"theme\": null}");

        assertThat(store.load()).contains(new AppConfig("/a", null));
    }

    @Test
    void readsAnUnknownThemeAsNoChoiceAndStillUsesTheDataFolder() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        for (String unknown : new String[] {"blue", "", "Dark"}) {
            Files.writeString(store.file(), "{\"dataFolder\": \"/a\", \"theme\": \"" + unknown + "\"}");

            assertThat(store.load()).as("theme '%s'", unknown).contains(new AppConfig("/a", null));
        }
    }

    @Test
    void aFileWithOnlyAThemeIsNotSetUpYetButItsThemeCanBeRead() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.file(), "{\"theme\": \"light\"}");

        assertThat(store.load()).isEmpty();
        assertThat(store.read()).contains(new AppConfig(null, "light"));
    }

    @Test
    void savesAndLoadsWhatALibraryChangeLeavesAndWritesNoneOfItWhenUnset() throws Exception {
        ConfigStore store = new ConfigStore(dir);

        store.save(new AppConfig("/new", "dark", "/old", true, true));

        assertThat(store.load()).contains(new AppConfig("/new", "dark", "/old", true, true));
        assertThat(store.file()).content().contains("\"previousDataFolder\" : \"/old\"", "\"changePending\" : true",
                "\"oldCopy\" : true");

        store.save(new AppConfig("/new", "dark"));

        assertThat(Files.readString(store.file())).doesNotContain("previousDataFolder", "changePending", "oldCopy");
    }

    @Test
    void anOlderFileHasNoChangeWaitingAndNoOldCopy() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.file(), "{\"dataFolder\": \"/a\", \"theme\": \"dark\"}");

        AppConfig config = store.load().orElseThrow();

        assertThat(config.previousDataFolder()).isNull();
        assertThat(config.pending()).isFalse();
        assertThat(config.hasOldCopy()).isFalse();
    }

    @Test
    void anUnknownThemeKeepsWhatALibraryChangeLeft() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.file(), "{\"dataFolder\": \"/a\", \"theme\": \"blue\", \"previousDataFolder\": \"/b\","
                + " \"changePending\": true, \"oldCopy\": true}");

        assertThat(store.load()).contains(new AppConfig("/a", null, "/b", true, true));
    }
}
