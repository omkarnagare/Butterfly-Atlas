package com.butterflyatlas.app.exports;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Removes a photo's location metadata for an export (design D11, 4b contract §3), always losslessly: the image data is
 * never decoded or re-encoded. The core only knows this interface; the desktop implementation
 * ({@code imaging.CommonsMetadataStripper}) uses Apache Commons Imaging.
 */
public interface MetadataStripper {

    /**
     * The photo (its bytes, the format read from them) without its location: GPS, XMP and IPTC removed, with the
     * Photoshop resources that share IPTC's segment, and the EXIF maker note removed whole (vendors such as Panasonic
     * store a city, state or landmark there); the rest of EXIF kept (make, model, date, exposure, copyright,
     * orientation, the thumbnail). A photo with nothing to remove comes back as it is, byte for byte. Fails with an {@link IOException} when the format is not one Butterfly Atlas reads or its
     * metadata cannot be rewritten.
     */
    byte[] strip(byte[] photo) throws IOException;

    /**
     * The read-back check after a copy is written: whether the file still carries a location (GPS in its EXIF or XMP)
     * or a maker note.
     * Fails with an {@link IOException} when the file or its metadata cannot be read (then nobody can say it has none).
     */
    boolean hasLocation(Path file) throws IOException;
}
