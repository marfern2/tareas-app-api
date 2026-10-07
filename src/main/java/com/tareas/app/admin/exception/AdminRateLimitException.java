package com.tareas.app.admin.exception;

public class AdminRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public AdminRateLimitException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
