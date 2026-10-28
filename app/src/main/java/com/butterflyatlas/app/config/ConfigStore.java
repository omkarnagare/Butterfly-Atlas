package com.butterflyatlas.app.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Reads and writes {@code config.json}. A missing or damaged file means "not set up yet". */
public final class ConfigStore {

    public static final String FILE_NAME = "config.json";

    private static final Logger LOG = LoggerFactory.getLogger(ConfigStore.class);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Path directory;

    public ConfigStore(Path directory) {
        this.directory = directory;
    }

    public Path file() {
        return directory.resolve(FILE_NAME);
    }

    /** The saved settings; empty when no library folder is saved yet (a file with only a theme is "not set up yet"). */
    public Optional<AppConfig> load() {
        return read().filter(config -> config.dataFolder() != null && !config.dataFolder().isBlank());
    }

    /**
     * The file as saved, with or without a library folder; an unknown theme is logged and read as none. Empty when the
     * file is missing or damaged.
     */
    public Optional<AppConfig> read() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            AppConfig config = JSON.readValue(file.toFile(), AppConfig.class);
            if (config == null) {
                return Optional.empty();
            }
            if (config.theme() != null && !AppConfig.isTheme(config.theme())) {
                LOG.warn("Ignoring unknown theme \"{}\" in {}", config.theme(), file);
                config = new AppConfig(config.dataFolder(), null);
            }
            return Optional.of(config);
        } catch (IOException e) {
            LOG.warn("Ignoring unreadable settings file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(AppConfig config) {
        Path file = file();
        Path temp = directory.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config) + "\n",
                    StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save settings to " + file, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                LOG.warn("Could not remove {}", temp);
            }
        }
    }
}
