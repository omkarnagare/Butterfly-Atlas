package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every sentence the app says calls the machine it runs on "device", never "computer" (match-device design §6), so the
 * words still fit a phone. Reads the string literals of the app's sources, comments left out.
 */
class DeviceWordingTest {

    private static final Path ROOT = Path.of("src/main/java/com/butterflyatlas/app");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    @Test
    void noStringSaysComputer() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = BLOCK_COMMENT.matcher(Files.readString(source)).replaceAll("");
                for (String line : code.split("\n")) {
                    if (line.strip().startsWith("//")) continue;
                    Matcher literal = LITERAL.matcher(line);
                    while (literal.find()) {
                        if (literal.group().toLowerCase().contains("computer")) {
                            found.add(ROOT.relativize(source) + ": " + literal.group());
                        }
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
