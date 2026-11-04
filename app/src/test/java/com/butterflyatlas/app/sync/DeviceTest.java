package com.butterflyatlas.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** This device's identity (one-device design §3): a random ID kept in {@code device-id}, and the name it already has. */
class DeviceTest {

    @TempDir
    Path config;

    @Test
    void theFirstStartMakesARandomIdAndKeepsItInTheConfigFolder() throws IOException {
        Device first = Device.load(config, () -> "Omkar's MacBook Air");

        assertThat(first.id()).matches("[0-9a-f-]{36}");
        assertThat(Files.readString(config.resolve(Device.ID_FILE)).strip()).isEqualTo(first.id());
        assertThat(first.name()).isEqualTo("Omkar's MacBook Air");
        assertThat(Device.load(config, () -> null).id()).isEqualTo(first.id());
    }

    @Test
    void twoConfigFoldersAreTwoDevices() {
        assertThat(Device.load(config.resolve("a"), () -> null).id())
                .isNotEqualTo(Device.load(config.resolve("b"), () -> null).id());
    }

    @Test
    void anEmptyOrDamagedIdFileIsReplaced() throws IOException {
        Files.writeString(config.resolve(Device.ID_FILE), "  \n");
        String made = Device.load(config, () -> null).id();
        assertThat(made).matches("[0-9a-f-]{36}");

        Files.writeString(config.resolve(Device.ID_FILE), "not/an id");
        assertThat(Device.load(config, () -> null).id()).matches("[0-9a-f-]{36}").isNotEqualTo("not/an id");
    }

    @Test
    void anIdThatCannotBeSavedIsAFailureNotAFreshIdEachStart() throws IOException {
        Path file = Files.writeString(config.resolve("blocked"), "a file, not a folder");

        assertThatThrownBy(() -> Device.load(file, () -> null)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void aNameThatCannotBeReadIsNull() {
        assertThat(Device.load(config, () -> "   ").name()).isNull();
        assertThat(Device.load(config, () -> {
            throw new IllegalStateException("no name");
        }).name()).isNull();
    }

    @Test
    void macOsAsksScutilForTheComputerName() {
        List<List<String>> ran = new ArrayList<>();
        String name = Device.systemName("Mac OS X", Map.of(), command -> {
            ran.add(command);
            return "Omkar's MacBook Air\n";
        }, () -> null);

        assertThat(name).isEqualTo("Omkar's MacBook Air");
        assertThat(ran).containsExactly(List.of("scutil", "--get", "ComputerName"));
    }

    @Test
    void windowsReadsComputername() {
        assertThat(Device.systemName("Windows 11", Map.of("COMPUTERNAME", "DESKTOP-PUNE"), command -> {
            throw new AssertionError("no command on Windows");
        }, () -> null)).isEqualTo("DESKTOP-PUNE");
        assertThat(Device.systemName("Windows 11", Map.of(), command -> null, () -> null)).isNull();
    }

    @Test
    void linuxReadsTheHostNameFileThenAsksHostname() {
        assertThat(Device.systemName("Linux", Map.of(), command -> "other", () -> "fedora-box\n")).isEqualTo("fedora-box");
        assertThat(Device.systemName("Linux", Map.of(), command -> {
            assertThat(command).containsExactly("hostname");
            return "ubuntu-box\n";
        }, () -> null)).isEqualTo("ubuntu-box");
        assertThat(Device.systemName("Linux", Map.of(), command -> {
            throw new IllegalStateException("no hostname");
        }, () -> "")).isNull();
    }
}
