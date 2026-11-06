package com.butterflyatlas.app.desktop.linux;

import com.butterflyatlas.app.testing.WindowChecks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-window check on Linux (spec §7, contract §5; tag {@value WindowChecks#TAG}, run with {@code -Pwindow-checks},
 * on CI under {@code xvfb-run}): Chrome started in app mode with {@link LinuxDesktop#browserArguments()} on a throwaway
 * profile shows a window whose WM_CLASS, read with {@code xprop}, contains {@value LinuxDesktop#WINDOW_CLASS}. The
 * browser's windows are told from the others by their {@code _NET_WM_PID}; the root window's children are listed with
 * {@code xwininfo} (also in x11-utils), since a virtual display has no window manager to list them. Skipped off Linux.
 */
@Tag(WindowChecks.TAG)
@EnabledOnOs(OS.LINUX)
class LinuxDesktopWindowTest {

    private static final Pattern WINDOW_ID = Pattern.compile("^\\s+(0x[0-9a-fA-F]+)\\s", Pattern.MULTILINE);

    @TempDir
    Path folder;

    private Process browser;

    @AfterEach
    void closeEverything() throws Exception {
        if (browser != null && browser.isAlive()) {
            browser.destroy();
            if (!browser.waitFor(10, TimeUnit.SECONDS)) {
                browser.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    /**
     * The WM_CLASS of every top-level window the browser made: the process started, or one it started (a launcher script
     * may start the browser program as a process of its own).
     */
    private static List<String> classesOf(Process started) throws IOException, InterruptedException {
        java.util.Set<Long> pids = new java.util.HashSet<>();
        pids.add(started.pid());
        started.descendants().forEach(child -> pids.add(child.pid()));
        List<String> classes = new ArrayList<>();
        Matcher ids = WINDOW_ID.matcher(run("xwininfo", "-root", "-children"));
        while (ids.find()) {
            String props = run("xprop", "-id", ids.group(1), "_NET_WM_PID", "WM_CLASS");
            Matcher pid = Pattern.compile("_NET_WM_PID\\(CARDINAL\\) = (\\d+)").matcher(props);
            if (pid.find() && pids.contains(Long.parseLong(pid.group(1))) && props.contains("WM_CLASS(STRING)")) {
                props.lines().filter(line -> line.startsWith("WM_CLASS")).forEach(classes::add);
            }
        }
        return classes;
    }

    @Test
    void theAppWindowCarriesOurWindowClass() throws Exception {
        Path program = WindowChecks.browser();
        List<String> command = new ArrayList<>(List.of(program.toString(), "--app=data:text/html,<title>Window check</title>",
                "--user-data-dir=" + folder.resolve("browser_profile"), "--no-first-run", "--no-default-browser-check"));
        command.addAll(new LinuxDesktop().browserArguments());
        browser = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        List<String> classes = List.of();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline && classes.isEmpty()) {
            Thread.sleep(250);
            classes = classesOf(browser);
        }

        assertThat(classes).as("the browser's windows (" + program + ")").isNotEmpty();
        assertThat(classes).anySatisfy(line -> assertThat(line).contains("\"" + LinuxDesktop.WINDOW_CLASS + "\""));
    }
}
