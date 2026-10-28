package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinWriterTest {

    private static final int[] SQUARE = {0, 0, 10, 0, 10, 10, 0, 10, 0, 0};

    @Test
    void writesTheExactContractBytes() throws IOException {
        byte[] bin = BinWriter.write(List.of(new BinWriter.Entry("P1", List.of(SQUARE))));

        assertThat(gunzip(bin)).containsExactly(
                'B', 'A', 'P', 'L', 1,          // magic, format
                1,                              // count
                2, 'P', '1',                    // id
                0, 0, 20, 20,                   // bbox 0,0,10,10 zig-zag
                1,                              // rings
                5,                              // points
                0, 0, 20, 0, 0, 20, 19, 0, 0, 19);  // (0,0) (+10,0) (0,+10) (-10,0) (0,-10)
    }

    @Test
    void everyRingStartsFromZeroAndAllRoundTripThroughAnIndependentReader() throws IOException {
        int[] far = {-17_999_999, -8_999_999, 17_999_999, -8_999_999, 17_999_999, 8_999_999, -17_999_999, -8_999_999};
        int[] hole = {2, 2, 4, 2, 4, 4, 2, 2};
        byte[] bin = BinWriter.write(List.of(
                new BinWriter.Entry("P412", List.of(SQUARE, hole)),
                new BinWriter.Entry("P7", List.of(far))));

        List<BinDecoder.Entry> entries = BinDecoder.decode(bin);

        assertThat(entries).extracting(BinDecoder.Entry::id).containsExactly("P412", "P7");
        assertThat(entries.get(0).bbox()).containsExactly(0, 0, 10, 10);
        assertThat(entries.get(0).rings()).containsExactly(SQUARE, hole);
        assertThat(entries.get(1).bbox()).containsExactly(-17_999_999, -8_999_999, 17_999_999, 8_999_999);
        assertThat(entries.get(1).rings()).containsExactly(far);
    }

    @Test
    void theFirstPointOfEveryRingIsADeltaFromZero() throws IOException {
        int[] second = {5, 5, 6, 5, 6, 6, 5, 5};
        byte[] raw = gunzip(BinWriter.write(List.of(new BinWriter.Entry("P1", List.of(SQUARE, second)))));

        // header 5, count 1, id 3, bbox 4, rings 1, first ring 1 + 10 bytes → the second ring starts at byte 25
        assertThat(java.util.Arrays.copyOfRange(raw, 25, raw.length)).containsExactly(
                4, 10, 10, 2, 0, 0, 2, 1, 1);    // 4 points: (5,5) from 0,0, then (+1,0) (0,+1) (-1,-1)
    }

    @Test
    void holesAreReadWithTheEvenOddRule() throws IOException {
        int[] outer = {0, 0, 100_000, 0, 100_000, 100_000, 0, 100_000, 0, 0};          // 1° square
        int[] hole = {20_000, 20_000, 80_000, 20_000, 80_000, 80_000, 20_000, 80_000, 20_000, 20_000};
        List<BinDecoder.Entry> entries = BinDecoder.decode(
                BinWriter.write(List.of(new BinWriter.Entry("P1", List.of(outer, hole)))));

        assertThat(BinDecoder.lookup(entries, 0.1, 0.1)).isEqualTo("P1");
        assertThat(BinDecoder.lookup(entries, 0.5, 0.5)).isNull();     // in the hole, 33 km from its edge
        assertThat(BinDecoder.lookup(entries, 0.21, 0.5)).isEqualTo("P1");   // in the hole, 1.1 km from its edge
    }

    @Test
    void largeValuesTakeSeveralVarintBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        BinWriter.svarint(out, 100_000);    // zig-zag 200,000 = 0x30D40 → 7-bit groups 0x40, 0x1A, 0x0C

        assertThat(out.toByteArray()).containsExactly(0xC0, 0x9A, 0x0C);
    }

    @Test
    void writesTheSameBytesEveryTime() throws IOException {
        List<BinWriter.Entry> entries = List.of(new BinWriter.Entry("P1", List.of(SQUARE)));

        assertThat(BinWriter.write(entries)).isEqualTo(BinWriter.write(entries));
    }

    @Test
    void refusesRingsTheFormatCannotHold() {
        assertThatThrownBy(() -> BinWriter.write(List.of(new BinWriter.Entry("P1", List.of(new int[]{0, 0, 1, 0, 0, 0})))))
                .hasMessageContaining("at least 4 points");
        assertThatThrownBy(() -> BinWriter.write(List.of(new BinWriter.Entry("P1", List.of(new int[]{0, 0, 1, 0, 1, 1, 0, 1})))))
                .hasMessageContaining("not closed");
        assertThatThrownBy(() -> BinWriter.write(List.of(new BinWriter.Entry("P1", List.of(new int[]{0, 0, 1, 0, 1, 1, 0, 0, 5})))))
                .hasMessage("A ring of P1 has an odd number of coordinates (9)");
        assertThatThrownBy(() -> BinWriter.write(List.of(new BinWriter.Entry("P1", List.of()))))
                .hasMessageContaining("no rings");
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }
}
