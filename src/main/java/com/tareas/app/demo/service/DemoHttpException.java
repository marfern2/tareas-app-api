package com.tareas.app.demo.service;

import org.springframework.http.HttpStatus;

public class DemoHttpException extends RuntimeException {
    private final HttpStatus status;

    public DemoHttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
