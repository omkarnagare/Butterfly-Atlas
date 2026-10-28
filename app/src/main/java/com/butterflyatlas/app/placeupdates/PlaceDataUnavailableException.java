package com.butterflyatlas.app.placeupdates;

/**
 * The library uses a place list this app does not ship (5c contract §4, §10): never guessed at, so the library is not
 * opened (trying again cannot help; the message says what to install or use).
 */
public final class PlaceDataUnavailableException extends RuntimeException {

    public PlaceDataUnavailableException(String message) {
        super(message);
    }
}
