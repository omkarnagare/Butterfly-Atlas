package com.butterflyatlas.app.taxonomy.bundle;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Parses bundle JSON. Unknown fields are ignored so older apps can read newer bundles of the same format. */
public final class BundleReader {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private BundleReader() {
    }

    public static Bundle read(byte[] json) throws IOException {
        Bundle bundle = JSON.readValue(json, Bundle.class);
        if (bundle == null || bundle.version() == null || bundle.group() == null) {
            throw new IOException("Not a taxonomy bundle: version or group is missing");
        }
        if (bundle.format() > Bundle.FORMAT) {
            throw new IOException("Bundle uses format " + bundle.format() + "; this version understands up to "
                    + Bundle.FORMAT);
        }
        return bundle;
    }
}
