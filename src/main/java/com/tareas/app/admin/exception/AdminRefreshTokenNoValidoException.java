package com.tareas.app.admin.exception;

public class AdminRefreshTokenNoValidoException extends RuntimeException {

    public AdminRefreshTokenNoValidoException() {
        super("Admin refresh token inválido");
    }
}
