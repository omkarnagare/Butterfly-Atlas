package com.butterflyatlas.app.db;

/** A database operation failed. Details are for the log, not for the user. */
public final class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
