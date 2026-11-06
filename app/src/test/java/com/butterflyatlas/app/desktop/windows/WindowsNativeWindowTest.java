package com.butterflyatlas.app.desktop.windows;

import com.butterflyatlas.app.testing.WindowChecks;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-window check on Windows (spec §7, contract §5; tag {@value WindowChecks#TAG}, run with {@code -Pwindow-checks}):
 * an Edge or Chrome app window on a throwaway profile, on a local page with a favicon, gets the four taskbar properties,
 * and 5 s later (after the browser has had every chance to set its own again) the shell still gives back what was set. A
 * second start on the same profile hands its window over and exits, and the browser process is then found from the
 * profile by the command-line lookup. The browser is closed at the end (WM_CLOSE; only this test destroys it if it stays).
 */
@Tag(WindowChecks.TAG)
@EnabledOnOs(OS.WINDOWS)
class WindowsNativeWindowTest {

    @TempDir
    Path folder;

    private final WindowsNative windows = new WindowsNative();
    private final List<Process> started = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void closeEverything() throws Exception {
        for (Process process : started) {
            if (process.isAlive()) {
                for (HWND window : windows.windowsOf(process.pid())) {
                    User32.INSTANCE.PostMessage(window, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
                }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS); // the test only: the app never force-kills
                }
            }
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /** A page with a favicon, served on 127.0.0.1; counts the favicon's requests. */
    private String serve(AtomicInteger favicons) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x2f7d4f));
        g.fillOval(2, 2, 28, 28);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] icon = png.toByteArray();
        byte[] page = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Window check</title>"
                + "<link rel=\"icon\" href=\"/favicon.png\"></head><body>A real-window check.</body></html>")
                .getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            boolean favicon = exchange.getRequestURI().getPath().startsWith("/favicon");
            if (favicon) {
                favicons.incrementAndGet();
            }
            byte[] body = favicon ? icon : page;
            exchange.getResponseHeaders().add("Content-Type", favicon ? "image/png" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private Process start(Path browser, Path profile, String address) throws Exception {
        Process process = new ProcessBuilder(browser.toString(), "--app=" + address, "--user-data-dir=" + profile,
                "--no-first-run", "--no-default-browser-check", "--start-maximized")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        started.add(process);
        return process;
    }

    private static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    @Test
    void theTaskbarPropertiesStickAndTheBrowserIsFoundFromTheProfile() throws Exception {
        Path browser = WindowChecks.browser();
        Path profile = folder.resolve("browser_profile");
        AtomicInteger favicons = new AtomicInteger();
        String address = serve(favicons);
        Path app = folder.resolve("Butterfly_Atlas").resolve("Butterfly Atlas.exe");

        Process first = start(browser, profile, address);
        assertThat(await(() -> !windows.windowsOf(first.pid()).isEmpty(), 30_000))
                .as("the app window of " + browser + " appeared").isTrue();
        await(() -> favicons.get() > 0, 15_000); // the page and its icon have loaded

        int set = windows.adopt(first.pid(), app);
        assertThat(set).isGreaterThanOrEqualTo(1);
        Thread.sleep(5_000);

        for (HWND window : windows.windowsOf(first.pid())) {
            for (List<String> value : WindowsNative.values(app)) {
                WindowsNative.Property property = WindowsNative.Property.valueOf(value.get(0));
                assertThat(windows.read(window, property)).as(property.name()).contains(value.get(1));
            }
        }

        Process second = start(browser, profile, address + "?second");
        assertThat(second.waitFor(30, TimeUnit.SECONDS)).as("the second start handed its window over and exited").isTrue();
        assertThat(windows.find(browser, profile)).hasValue(first.pid());
        assertThat(first.isAlive()).isTrue();
    }
}
