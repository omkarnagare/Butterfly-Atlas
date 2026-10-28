package com.butterflyatlas.app.web;

/** The JSON error shape: {@code {"error": {"code": "not_found", "message": "…"}}}. */
public record ApiError(Detail error) {

    public record Detail(String code, String message) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(new Detail(code, message));
    }
}
