package com.hanwang.facekey.thin.net;

/** A network/protocol error that is safe to show in the UI. */
public class ApiException extends Exception {
    public final String code;
    public final int httpStatus;

    public ApiException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public ApiException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }
}
