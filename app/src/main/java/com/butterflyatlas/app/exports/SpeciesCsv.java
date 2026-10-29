package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.filters.FilterQuery.ListRow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The species list as a CSV file that opens in Excel (design D12, 4b contract §4): UTF-8 with a byte-order mark, CRLF
 * line ends, RFC 4180 quoting, and a cell that a spreadsheet would run as a formula ({@code = + - @}, tab or CR first)
 * given a leading {@code '}.
 */
public final class SpeciesCsv {

    private static final List<String> PLACES = List.of("Country", "State", "District");

    private SpeciesCsv() {
    }

    /**
     * Writes the header and the rows; the place columns go down to {@code depth} (0 none, 1 country, 2 state, 3
     * district) and the Missing column is there with Life stages: Missing. The stream is flushed, not closed.
     */
    public static void write(OutputStream out, List<ListRow> rows, int depth, boolean missing) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write('\uFEFF'); // the byte-order mark Excel needs to read UTF-8
        List<String> header = new ArrayList<>(List.of("Family", "Subfamily", "Tribe", "Genus", "Species", "Common name"));
        header.addAll(PLACES.subList(0, depth));
        header.addAll(List.of("Photo count", "Tags present"));
        if (missing) {
            header.add("Missing");
        }
        header.addAll(List.of("Source", "Atlas ID"));
        line(writer, header);
        for (ListRow row : rows) {
            List<String> cells = new ArrayList<>(List.of(nz(row.family()), nz(row.subfamily()), nz(row.tribe()),
                    nz(row.genus()), nz(row.name()), nz(row.common())));
            List<String> places = new ArrayList<>();
            places.add(row.country());
            places.add(row.state());
            places.add(row.district());
            places.subList(0, depth).forEach(p -> cells.add(nz(p)));
            cells.add(Integer.toString(row.photos()));
            cells.add(String.join(", ", row.tags()));
            if (missing) {
                cells.add(String.join(", ", row.missing()));
            }
            cells.add("manual".equals(row.source()) ? "Custom" : "Official");
            cells.add(row.id());
            line(writer, cells);
        }
        writer.flush();
    }

    /** One cell as written: formula-escaped, then quoted when it holds {@code "}, a comma, CR or LF. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (text.indexOf('"') >= 0 || text.indexOf(',') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private static void line(Writer writer, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(cell(cells.get(i)));
        }
        writer.write("\r\n");
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
