package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A RAW photo's preview for the viewer, read from the channel that was checked (fixes J9). */
class RawDisplaysTest {

    @TempDir
    Path dir;

    private final byte[] preview = TestImages.jpeg(1024, 768, 7);

    private Path nef() throws Exception {
        return Files.write(dir.resolve("DSC_0001.NEF"), TestRaw.tiff(new TestRaw.Ifd().jpeg(preview)));
    }

    @Test
    void aPreviewFoundWithoutStoredDetailsSaysWhereItWasFound() throws Exception {
        Path file = nef();

        try (RawDisplays.Preview found = RawDisplays.of(file, null, 0, null); InputStream in = found.open()) {
            assertThat(in.readAllBytes()).isEqualTo(preview);
            assertThat(found.found()).isEqualTo(new RawDisplays.Found(found.offset(), preview.length, Files.size(file),
                    Files.getLastModifiedTime(file).toInstant().toString()));
        }
    }

    @Test
    void theBytesComeFromTheFileThatWasCheckedEvenIfItIsReplacedMeanwhile() throws Exception {
        Path file = nef();
        RawDisplays.Preview checked = RawDisplays.of(file, null, 0, null);
        Files.delete(file);
        Files.write(file, new byte[] {1, 2, 3}); // another file under the name
        try (InputStream in = checked.open()) {
            assertThat(in.readAllBytes()).isEqualTo(preview);
        } catch (java.nio.file.FileSystemException e) {
            // Windows: an open file cannot be deleted; nothing to check there
        }
    }

    @Test
    void aFileCutShortWhileSentFailsInsteadOfEndingEarly() throws Exception {
        Path file = nef();
        RawDisplays.Preview checked = RawDisplays.of(file, null, 0, null);
        try (InputStream in = checked.open()) {
            try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
                channel.truncate(checked.offset() + 10);
            }
            assertThatThrownBy(in::readAllBytes).isInstanceOf(EOFException.class);
        }

        RawDisplays.Preview again = RawDisplays.of(nef(), null, 0, null);
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            channel.truncate(again.offset() + 10);
        }
        assertThatThrownBy(again::open).as("cut short before it is sent").isInstanceOf(java.io.IOException.class);
        assertThat(again.channel().isOpen()).as("closed on the failure").isFalse();
    }

    @Test
    void closingAPreviewClosesItsFileAndAFileWithoutOneIsRefused() throws Exception {
        RawDisplays.Preview checked = RawDisplays.of(nef(), null, 0, null);
        checked.close();
        assertThat(checked.channel().isOpen()).isFalse();
        assertThatThrownBy(() -> checked.open().read()).isInstanceOf(ClosedChannelException.class);

        Path bare = Files.write(dir.resolve("DSC_0002.NEF"), TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 1)));
        assertThatThrownBy(() -> RawDisplays.of(bare, null, 0, null)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo(PhotoFormat.RAW_PREVIEW_UNREADABLE));
    }
}
