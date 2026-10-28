package com.butterflyatlas.app.photos;

import java.io.IOException;

/**
 * A file that is a photo the app knows but cannot take (a RAW file without a usable preview).
 * The message is the plain-language reason shown to the user, e.g. {@link PhotoFormat#RAW_NO_PREVIEW}.
 */
public final class UnsupportedPhotoException extends IOException {

    public UnsupportedPhotoException(String reason) {
        super(reason);
    }
}
