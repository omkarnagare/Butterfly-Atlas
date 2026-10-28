package com.butterflyatlas.app.imaging.heif;

import com.butterflyatlas.app.testing.TestExif;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Makes the committed HEIC files from pictures this code draws (nothing anyone else owns), through macOS's own encoder
 * ({@code /usr/bin/sips}, which turns the source JPEG's EXIF orientation into the HEIC's {@code irot} and {@code imir}):
 * the decoder check's probes ({@code app/src/main/resources/heif/probe-rot.heic}: 64 × 32, left half red, right half
 * blue, a white top-left square, {@code irot} 1; {@code probe-mirror.heic}: the same with {@code imir} 1) and the smoke
 * test's {@code packaging/smoke/fixtures/heic.heic} (1024 × 768, {@code irot} 1, GPS in Pune).
 *
 * <p>To make them again, on a Mac, from the repository root (after {@code ./mvnw -pl ui,app -Dskip.npm
 * -Dskip.installnodenpm test-compile}):
 * {@code java -cp app/target/test-classes:app/target/classes:$(cat cp.txt) com.butterflyatlas.app.imaging.heif.HeicFixtures .}
 * where {@code cp.txt} is the test class path ({@code ./mvnw -pl app dependency:build-classpath -Dmdep.outputFile=cp.txt}).
 * {@code HeicFixturesTest} checks the committed files are what this says.
 */
public final class HeicFixtures {

    static final Path SIPS = Path.of("/usr/bin/sips");

    private HeicFixtures() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path resources = Files.createDirectories(root.resolve("app/src/main/resources/heif"));
        Files.write(resources.resolve("probe-rot.heic"), heic(probeJpeg(8)));
        Files.write(resources.resolve("probe-mirror.heic"), heic(probeJpeg(2)));
        Files.write(root.resolve("packaging/smoke/fixtures/heic.heic"), heic(smokeJpeg()));
    }

    /** The probe picture (64 × 32) as a JPEG whose EXIF says {@code orientation}. */
    static byte[] probeJpeg(int orientation) throws IOException {
        return TestExif.camera().orientation(orientation).jpeg(jpeg(probePicture()));
    }

    /** 64 × 32: left half red, right half blue, a white 8 × 8 square at the top left. */
    static BufferedImage probePicture() {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 32, 32);
        g.setColor(Color.BLUE);
        g.fillRect(32, 0, 32, 32);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        return image;
    }

    /** 1024 × 768, a gradient with a white top-left square, GPS in Pune, captured 2024-05-01, EXIF orientation 8. */
    static byte[] smokeJpeg() throws IOException {
        return TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(18.5204, 73.8567, 560).orientation(8)
                .jpeg(jpeg(gradient(1024, 768)));
    }

    /** A {@code width × height} gradient (green to orange) with a white square at the top left. */
    public static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(40, 140, 60), width, height, new Color(230, 140, 40)));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width / 8, width / 8);
        g.dispose();
        return image;
    }

    public static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /** The JPEG through {@code sips -s format heic}: Apple's encoder, which writes the EXIF orientation as irot/imir. */
    public static byte[] heic(byte[] jpeg) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("atlas-heic-fixture");
        try {
            Path in = dir.resolve("in.jpg");
            Path out = dir.resolve("out.heic");
            Files.write(in, jpeg);
            Process sips = new ProcessBuilder(List.of(SIPS.toString(), "-s", "format", "heic", in.toString(), "--out",
                    out.toString())).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!sips.waitFor(60, TimeUnit.SECONDS) || sips.exitValue() != 0 || !Files.exists(out)) {
                sips.destroyForcibly();
                throw new IOException("sips could not make a HEIC");
            }
            return Files.readAllBytes(out);
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
    }
}
