package com.butterflyatlas.app.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * This device (one-device design §3): a random ID made on first start and kept as {@code device-id} in the config folder,
 * and the name the device already has, for messages ({@code name} is null when it can't be read: messages then say
 * "another device"). There is no setting for either.
 */
public record Device(String id, String name) {

    private static final Logger LOG = LoggerFactory.getLogger(Device.class);

    /** The file in the config folder that keeps this device's ID. */
    public static final String ID_FILE = "device-id";
    private static final Pattern ID = Pattern.compile("[0-9A-Za-z-]{8,64}");

    /**
     * This device: its ID read from {@code configDir}, or made and saved there on first start; its name from {@code name}
     * (blank, or failing: null). An ID that can't be saved throws {@link UncheckedIOException}: a fresh ID at every start
     * would make this device's own marker look like another device's.
     */
    public static Device load(Path configDir, Supplier<String> name) {
        return new Device(id(configDir), safeName(name));
    }

    private static String id(Path configDir) {
        Path file = configDir.resolve(ID_FILE);
        try {
            if (Files.isRegularFile(file)) {
                String saved = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (ID.matcher(saved).matches()) {
                    return saved;
                }
                LOG.warn("The device ID in {} can't be used; a new one is made", file);
            }
        } catch (IOException e) {
            LOG.warn("Could not read the device ID in {}; a new one is made", file, e);
        }
        String made = UUID.randomUUID().toString();
        Path temp = configDir.resolve(ID_FILE + ".tmp");
        try {
            Files.createDirectories(configDir);
            Files.writeString(temp, made + "\n", StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // the folder can't be written: the failure below says so
            }
            throw new UncheckedIOException("Could not save this device's ID in " + file, e);
        }
        LOG.info("This device's ID for libraries is {}", made);
        return made;
    }

    private static String safeName(Supplier<String> name) {
        try {
            String read = name.get();
            return read == null || read.isBlank() ? null : read.strip();
        } catch (RuntimeException e) {
            LOG.warn("Could not read this device's name; messages say \"another device\"", e);
            return null;
        }
    }

    /** The name of this device as the operating system gives it (null when it can't be read). */
    public static String systemName() {
        return systemName(System.getProperty("os.name", ""), System.getenv(), Device::run, Device::etcHostname);
    }

    /**
     * The device's name: on macOS {@code scutil --get ComputerName}, on Windows {@code COMPUTERNAME}, elsewhere the host
     * name ({@code /etc/hostname}, else {@code hostname}). Null when it can't be read. Nothing goes online.
     */
    static String systemName(String os, Map<String, String> env, Function<List<String>, String> run,
                             Supplier<String> hostnameFile) {
        String lower = os.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("mac")) {
                return clean(run.apply(List.of("scutil", "--get", "ComputerName")));
            }
            if (lower.startsWith("windows")) {
                return clean(env.get("COMPUTERNAME"));
            }
            String file = clean(hostnameFile.get());
            return file != null ? file : clean(run.apply(List.of("hostname")));
        } catch (RuntimeException e) {
            LOG.warn("Could not read this device's name", e);
            return null;
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.isEmpty() ? null : line;
    }

    private static String etcHostname() {
        try {
            Path file = Path.of("/etc/hostname");
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Runs a short command and answers what it printed (null when it failed or took more than 3 seconds). */
    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
