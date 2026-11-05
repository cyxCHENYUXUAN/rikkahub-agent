package com.rikkahub.platform.http;

/** Only deliberately safe, user-facing details belong in this exception. */
public final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;

    public ApiFailure(int status, String code, String detail) {
        super(detail);
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
