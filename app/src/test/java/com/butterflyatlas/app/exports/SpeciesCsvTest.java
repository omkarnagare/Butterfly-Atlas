package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.filters.FilterQuery.ListRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The species list file (4b contract §4): UTF-8 with BOM, CRLF, RFC 4180, formula cells escaped. */
class SpeciesCsvTest {

    private static final ListRow TIGER = new ListRow("A5", "Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus chrysippus",
            "Plain Tiger", "India", "Maharashtra", "Pune", 3, List.of("egg", "upper wing"), List.of("larva", "pupa"), "official");
    private static final ListRow MINE = new ListRow("U1", null, null, null, null, "Danaus novus", null, null, null, null, 0,
            List.of(), List.of(), "manual");

    private static String csv(List<ListRow> rows, int depth, boolean missing) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SpeciesCsv.write(out, rows, depth, missing);
        byte[] bytes = out.toByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    void theColumnsWithoutPlacesOrMissing() throws Exception {
        assertThat(csv(List.of(TIGER, MINE), 0, false)).isEqualTo(
                "Family,Subfamily,Tribe,Genus,Species,Common name,Photo count,Tags present,Source,Atlas ID\r\n"
                        + "Nymphalidae,Danainae,Danaini,Danaus,Danaus chrysippus,Plain Tiger,3,\"egg, upper wing\",Official,A5\r\n"
                        + ",,,,Danaus novus,,0,,Custom,U1\r\n");
    }

    @Test
    void placeColumnsDownToTheDepthAndMissingWithMissingLifeStages() throws Exception {
        assertThat(csv(List.of(TIGER), 1, false)).startsWith("Family,Subfamily,Tribe,Genus,Species,Common name,Country,Photo count,")
                .contains(",Plain Tiger,India,3,");
        assertThat(csv(List.of(TIGER), 2, false)).contains(",Country,State,Photo count,").contains(",India,Maharashtra,3,");
        assertThat(csv(List.of(TIGER), 3, true)).isEqualTo(
                "Family,Subfamily,Tribe,Genus,Species,Common name,Country,State,District,Photo count,Tags present,Missing,"
                        + "Source,Atlas ID\r\n"
                        + "Nymphalidae,Danainae,Danaini,Danaus,Danaus chrysippus,Plain Tiger,India,Maharashtra,Pune,3,"
                        + "\"egg, upper wing\",\"larva, pupa\",Official,A5\r\n");
    }

    @Test
    void cellsAreQuotedAsRfc4180Says() {
        assertThat(SpeciesCsv.cell("plain")).isEqualTo("plain");
        assertThat(SpeciesCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(SpeciesCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SpeciesCsv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(SpeciesCsv.cell("cr\rhere")).isEqualTo("\"cr\rhere\"");
        assertThat(SpeciesCsv.cell(null)).isEmpty();
        assertThat(SpeciesCsv.cell("Gräfin")).isEqualTo("Gräfin");
    }

    @Test
    void cellsThatASpreadsheetWouldRunAsAFormulaAreEscaped() {
        assertThat(SpeciesCsv.cell("=1+2")).isEqualTo("'=1+2");
        assertThat(SpeciesCsv.cell("+91 tiger")).isEqualTo("'+91 tiger");
        assertThat(SpeciesCsv.cell("-x")).isEqualTo("'-x");
        assertThat(SpeciesCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SpeciesCsv.cell("\tx")).isEqualTo("'\tx");
        assertThat(SpeciesCsv.cell("\rx")).isEqualTo("\"'\rx\"");
        assertThat(SpeciesCsv.cell("=HYPERLINK(\"x\",\"y\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\"");
        assertThat(SpeciesCsv.cell("a-b")).isEqualTo("a-b");
    }
}
