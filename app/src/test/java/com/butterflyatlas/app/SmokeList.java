package com.butterflyatlas.app;

import java.util.List;

/**
 * Reads {@code packaging/smoke/routes.txt}: one route per line, {@code METHOD /path}, the path as the server registers it
 * ({@code /api/exports/{id}}); what the smoke test sends is written after a {@code |} and is not this test's business.
 * Blank lines and lines starting with {@code #} are ignored.
 */
final class SmokeList {

    private SmokeList() {
    }

    static List<String> routes(List<String> lines) {
        return lines.stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.contains("|") ? line.substring(0, line.indexOf('|')).strip() : line)
                .toList();
    }
}
