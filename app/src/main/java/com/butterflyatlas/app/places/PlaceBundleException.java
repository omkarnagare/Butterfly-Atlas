package com.butterflyatlas.app.places;

/** The bundled place data is missing, damaged or inconsistent. The app cannot start without it. */
public final class PlaceBundleException extends RuntimeException {

    public PlaceBundleException(String message) {
        super(message);
    }

    public PlaceBundleException(String message, Throwable cause) {
        super(message, cause);
    }
}
