package com.butterflyatlas.importer.inat;

import java.io.IOException;
import java.net.URI;

/** Fetches a URL and returns the response body. */
public interface Transport {
    String get(URI uri) throws IOException;
}
