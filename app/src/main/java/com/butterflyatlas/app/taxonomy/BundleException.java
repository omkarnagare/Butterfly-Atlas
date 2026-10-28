package com.butterflyatlas.app.taxonomy;

/** A shipped taxonomy bundle is missing, damaged or inconsistent. The app cannot start without it. */
public final class BundleException extends RuntimeException {

    public BundleException(String message) {
        super(message);
    }

    public BundleException(String message, Throwable cause) {
        super(message, cause);
    }
}
