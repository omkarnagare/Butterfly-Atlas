package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoFormatTest {

    private static byte[] head(byte[] file) {
        return Arrays.copyOf(file, Math.min(file.length, PhotoFormat.HEAD_BYTES));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void recognisesEachFormatByItsFirstBytes() {
        assertThat(PhotoFormat.detect(head(TestImages.jpeg(8, 8)))).contains(PhotoFormat.JPEG);
        assertThat(PhotoFormat.detect(head(TestImages.png(8, 8)))).contains(PhotoFormat.PNG);
        assertThat(PhotoFormat.detect(head(TestImages.tiff(8, 8)))).contains(PhotoFormat.TIFF);
        assertThat(PhotoFormat.detect(head(TestImages.webp()))).contains(PhotoFormat.WEBP);
        assertThat(PhotoFormat.detect(ascii("MM\0*\0\0\0\u0008"))).contains(PhotoFormat.TIFF);
    }

    @Test
    void anythingElseIsNotAPhoto() {
        assertThat(PhotoFormat.detect(ascii("GIF89a"))).isEmpty();
        assertThat(PhotoFormat.detect(ascii("RIFF\0\0\0\0WAVE"))).isEmpty();
        assertThat(PhotoFormat.detect(new byte[0])).isEmpty();
        assertThat(PhotoFormat.refusal(ascii("%PDF-1.7"))).isEqualTo("This file isn't a photo Butterfly Atlas can read.");
    }

    @Test
    void heicStillImagesAreRecognisedByTheirBrands() {
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u0018ftypheic\0\0\0\0mif1heic"))).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u0018ftypheix\0\0\0\0mif1heix"))).as("camera .HIF").contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u0014ftypheim\0\0\0\0mif1"))).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u0014ftypheis\0\0\0\0mif1"))).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u001cftypmif1\0\0\0\0mif1heicmiaf"))).as("Samsung").contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u001cftypmiaf\0\0\0\0miafheixmif1"))).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.HEIC.fileType()).isEqualTo("heic");
        assertThat(PhotoFormat.HEIC.extension()).isEqualTo("heic");
        assertThat(PhotoFormat.HEIC.contentType()).isEqualTo("image/heic");
    }

    @Test
    void heifSequencesAndOtherCodingsGetTheirOwnReasons() {
        byte[] msf1 = ascii("\0\0\0\u0018ftypmsf1\0\0\0\0msf1heic");
        byte[] hevc = ascii("\0\0\0\u0018ftyphevc\0\0\0\0mif1hevc");
        byte[] hevx = ascii("\0\0\0\u0014ftyphevx\0\0\0\0mif1");
        byte[] mif1Sequence = ascii("\0\0\0\u0018ftypmif1\0\0\0\0mif1msf1");
        byte[] jpegInHeif = ascii("\0\0\0\u0018ftypmif1\0\0\0\0mif1jpeg");

        for (byte[] sequence : new byte[][] {msf1, hevc, hevx, mif1Sequence}) {
            assertThat(PhotoFormat.detect(sequence)).isEmpty();
            assertThat(PhotoFormat.refusal(sequence)).isEqualTo("HEIC image sequences (several frames in one file) aren't"
                    + " supported. Save the frame you want as a photo and add that.");
        }
        assertThat(PhotoFormat.detect(jpegInHeif)).isEmpty();
        assertThat(PhotoFormat.refusal(jpegInHeif)).isEqualTo("This HEIF photo is stored in a way Butterfly Atlas can't"
                + " read. Save it as JPEG and add that.");
    }

    @Test
    void rawFilesWithHeadersOfTheirOwnAreRecognisedByTheirBytes() {
        assertThat(PhotoFormat.detect(ascii("II*\0\u0010\0\0\0CR\u0002\0"))).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.detect(ascii("\0\0\0\u0018ftypcrx \0\0\0\0"))).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.detect(ascii("FUJIFILMCCD-RAW 0201"))).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.detect(ascii("IIRO\u0008\0\0\0"))).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.detect(ascii("IIRS\u0008\0\0\0"))).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.detect(ascii("MMOR\0\0\0\u0008"))).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.detect(ascii("IIU\0\u0018\0\0\0"))).contains(PhotoFormat.RAW);
        // a NEF, ARW or PEF is a TIFF to its first bytes: PhotoIngest tells it apart by name or DNGVersion
        assertThat(PhotoFormat.detect(head(TestImages.tiff(8, 8)))).contains(PhotoFormat.TIFF);
        assertThat(PhotoFormat.RAW_NO_PREVIEW).isEqualTo("This RAW photo has no preview Butterfly Atlas can show."
                + " Save it as JPEG from your RAW software and add that.");
    }

    @Test
    void fileTypesAndRawExtensions() {
        assertThat(PhotoFormat.JPEG.fileType()).isEqualTo("jpeg");
        assertThat(PhotoFormat.RAW.fileType()).isEqualTo("raw");
        assertThat(PhotoFormat.fromFileType("tiff")).contains(PhotoFormat.TIFF);
        assertThat(PhotoFormat.fromFileType("tif")).isEmpty();
        assertThat(PhotoFormat.rawExtension("DSC_0042.NEF")).contains("nef");
        assertThat(PhotoFormat.rawExtension("IMG_1.CR3")).contains("cr3");
        assertThat(PhotoFormat.rawExtension("scan.tif")).isEmpty();
        assertThat(PhotoFormat.fromFileName("Danaus_chrysippus_01.cr3")).as("a RAW name keeps its own extension").isEmpty();
        assertThat(PhotoFormat.RAW.contentType()).isEqualTo("application/octet-stream");
        assertThatThrownBy(PhotoFormat.RAW::extension).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void avifGetsItsOwnReason() {
        byte[] avif = ascii("\0\0\0\u001cftypavif\0\0\0\0avifmif1miaf");
        byte[] sequence = ascii("\0\0\0\u0018ftypavis\0\0\0\0avis");
        byte[] mif1Av01 = ascii("\0\0\0\u001cftypmif1\0\0\0\0mif1miafav01");
        byte[] mif1Heic = ascii("\0\0\0\u001cftypmif1\0\0\0\0mif1heicmiaf");

        assertThat(PhotoFormat.detect(avif)).isEmpty();
        assertThat(PhotoFormat.refusal(avif)).isEqualTo("AVIF photos aren't supported yet.");
        assertThat(PhotoFormat.refusal(sequence)).isEqualTo("AVIF photos aren't supported yet.");
        assertThat(PhotoFormat.refusal(mif1Av01)).isEqualTo("AVIF photos aren't supported yet.");
        assertThat(PhotoFormat.detect(mif1Heic)).contains(PhotoFormat.HEIC);
    }

    @Test
    void rawFileNamesAreKnownWhateverTheirCase() {
        for (String name : new String[] {"a.nef", "a.NRW", "a.dng", "a.arw", "a.srf", "a.sr2", "a.cr2", "a.CR3", "a.pef", "a.srw",
                "a.orf", "a.rw2", "a.raf", "a.3fr", "a.iiq", "a.RWL"}) {
            assertThat(PhotoFormat.isRawFileName(name)).as(name).isTrue();
        }
        assertThat(PhotoFormat.isRawFileName("scan.tif")).isFalse();
        assertThat(PhotoFormat.isRawFileName("nef")).isFalse();
        assertThat(PhotoFormat.isRawFileName(null)).isFalse();
    }

    @Test
    void contentTypesAndFileNames() {
        assertThat(PhotoFormat.fromContentType("image/JPEG; charset=x")).contains(PhotoFormat.JPEG);
        assertThat(PhotoFormat.fromContentType("image/webp")).contains(PhotoFormat.WEBP);
        assertThat(PhotoFormat.fromContentType("image/heic")).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.fromContentType("image/heif-sequence")).as("the bytes decide").contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.fromContentType("image/x-raw")).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.fromContentType("image/x-canon-cr3")).contains(PhotoFormat.RAW);
        assertThat(PhotoFormat.fromContentType("application/octet-stream")).isEmpty();
        assertThat(PhotoFormat.fromContentType(null)).isEmpty();
        assertThat(PhotoFormat.fromFileName("IMG_1.JPEG")).contains(PhotoFormat.JPEG);
        assertThat(PhotoFormat.fromFileName("scan.tiff")).contains(PhotoFormat.TIFF);
        assertThat(PhotoFormat.fromFileName("notes.txt")).isEmpty();
        assertThat(PhotoFormat.fromFileName("IMG_0042.HEIC")).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.fromFileName("a.heif")).contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.fromFileName("A7S_0001.HIF")).as("camera HEIF").contains(PhotoFormat.HEIC);
        assertThat(PhotoFormat.fromFileName("README")).isEmpty();
        assertThat(PhotoFormat.TIFF.extension()).isEqualTo("tif");
        assertThat(PhotoFormat.PNG.contentType()).isEqualTo("image/png");
    }
}
