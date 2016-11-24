package com.google.fcm;

import lombok.Getter;

import java.io.IOException;

/**
 * @author André Alexandre
 * @since 1.0.0
 */
@Getter
public final class InvalidRequestException extends IOException {

    private final int httpStatusCode;
    private final String description;

    public InvalidRequestException(int httpStatusCode) {
        this(httpStatusCode, null);
    }

    public InvalidRequestException(int httpStatusCode, String description) {
        super(getMessage(httpStatusCode, description));
        this.httpStatusCode = httpStatusCode;
        this.description = description;
    }

    private static String getMessage(int status, String description) {
        StringBuilder base = new StringBuilder("HTTP Status Code: ").append(status);
        if (description != null) {
            base.append("(").append(description).append(")");
        }
        return base.toString();
    }

}