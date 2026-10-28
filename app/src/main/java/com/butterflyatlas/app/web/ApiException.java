package com.butterflyatlas.app.web;

/** An HTTP-level refusal (forbidden host or origin, wrong content type) with its status and code. */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
