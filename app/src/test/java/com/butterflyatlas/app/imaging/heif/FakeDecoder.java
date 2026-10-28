package com.butterflyatlas.app.imaging.heif;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A stand-in for a HEIC decoder program, started as a separate process by the tests ({@link #command}):
 * {@code FakeDecoder <mode> <width> <height> <input> <output> <longSide>}. It decodes nothing; what it does is chosen by
 * its mode: {@code ok} writes a {@code width × height} JPEG (left half red, right half blue, a white top-left square);
 * {@code hang} never ends; {@code crash} says something and exits with 3; {@code garbage} writes bytes that are not a
 * JPEG; {@code huge} writes a file over 64 MB; {@code extra} writes the JPEG and two more files beside it, as
 * heif-convert does for depth images; {@code echo} prints its arguments and its standard input, then writes the JPEG;
 * {@code child} starts a child process that never ends, prints its pid, and never ends either; {@code loud} prints
 * 1 MB, then writes the JPEG; {@code none} exits 0 without writing anything; {@code slow} waits 1.5 s, then writes the
 * JPEG; {@code rot-stored}, {@code rot-applied}, {@code mirror-applied}, {@code white-br} answer a probe (the white square
 * where a decoder that leaves the picture as stored, turns it, mirrors it, or does something else would put it);
 * {@code say} prints its seventh argument and exits with 4.
 */
public final class FakeDecoder {

    private FakeDecoder() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        Path output = Path.of(args[4]);
        switch (mode) {
            case "ok" -> jpeg(width, height, output);
            case "slow" -> {
                Thread.sleep(1500);
                jpeg(width, height, output);
            }
            case "hang" -> Thread.sleep(Long.MAX_VALUE);
            case "crash" -> {
                System.out.println("fake decoder: cannot decode this");
                System.exit(3);
            }
            case "garbage" -> Files.write(output, "not a jpeg at all".getBytes(StandardCharsets.US_ASCII));
            case "huge" -> {
                try (RandomAccessFile file = new RandomAccessFile(output.toFile(), "rw")) {
                    file.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
                    file.setLength(65L * 1024 * 1024);
                }
            }
            case "extra" -> {
                jpeg(width, height, output);
                String base = output.getFileName().toString().replace(".jpg", "");
                Files.write(output.resolveSibling(base + "-depth.jpg"), new byte[] {1});
                // heif-convert's name for an auxiliary image; Windows allows no ':' in a file name
                String aux = java.io.File.separatorChar == '/' ? "-urn:com:apple:photo:2020:aux:hdrgainmap.jpg"
                        : "-urn_com_apple_photo_2020_aux_hdrgainmap.jpg";
                Files.write(output.resolveSibling(base + aux), new byte[] {2});
            }
            case "echo" -> {
                System.out.println("args=" + String.join("|", Arrays.asList(args)));
                System.out.println("stdin=" + new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
                System.out.println("env=" + System.getenv("ATLAS_IN"));
                jpeg(width, height, output);
            }
            case "child" -> {
                Process child = new ProcessBuilder(javaPath(), "-Djava.awt.headless=true", "-XX:TieredStopAtLevel=1",
                        "-cp", classPath(), FakeDecoder.class.getName(), "hang", "1",
                        "1", "x", "y", "1").start();
                System.out.println("child=" + child.pid());
                System.out.flush();
                Thread.sleep(Long.MAX_VALUE);
            }
            case "loud" -> {
                byte[] line = "noise noise noise noise noise noise noise noise noise noise noise noise noise\n"
                        .getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; i < (1024 * 1024) / line.length; i++) {
                    System.out.write(line);
                }
                System.out.flush();
                jpeg(width, height, output);
            }
            case "rot-stored" -> probe(64, 32, "top-left", output);
            case "rot-applied" -> probe(32, 64, "bottom-left", output);
            case "mirror-applied" -> probe(64, 32, "top-right", output);
            case "white-br" -> probe(64, 32, "bottom-right", output);
            case "say" -> {
                System.out.println(args.length > 6 ? args[6] : "");
                System.exit(4);
            }
            case "none" -> {
                // says it succeeded, writes nothing
            }
            default -> System.exit(2);
        }
    }

    /** The JPEG {@code ok} writes: left half red, right half blue, a white square at the top left. */
    static void jpeg(int width, int height, Path output) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, Math.max(1, width / 8), Math.max(1, width / 8));
        g.dispose();
        ImageIO.write(image, "jpeg", output.toFile());
    }

    /** A probe's answer: red and blue halves, the white square in {@code corner}. */
    static void probe(int width, int height, String corner, Path output) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        int x = corner.endsWith("left") ? 0 : width - 8;
        int y = corner.startsWith("top") ? 0 : height - 8;
        g.fillRect(x, y, 8, 8);
        g.dispose();
        ImageIO.write(image, "jpeg", output.toFile());
    }

    static String javaPath() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Where this class and {@link DecoderRun} were loaded from: it needs nothing else but the JDK. */
    static String classPath() {
        try {
            return Path.of(FakeDecoder.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    + java.io.File.pathSeparator
                    + Path.of(DecoderRun.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A {@link DecoderRun.Command} that starts this fake in {@code mode}, writing a {@code width × height} picture. */
    public static DecoderRun.Command command(String mode, int width, int height) {
        return new DecoderRun.Command() {
            @Override
            public List<String> arguments(Path input, Path output, int longSide) {
                return List.of(javaPath(), "-Djava.awt.headless=true", "-XX:TieredStopAtLevel=1", "-cp", classPath(),
                        FakeDecoder.class.getName(), mode, String.valueOf(width),
                        String.valueOf(height), input.toString(), output.toString(), String.valueOf(longSide));
            }

            @Override
            public String input(Path input, Path output, int longSide) {
                return mode.equals("echo") ? "fixed script text" : null;
            }
        };
    }
}
