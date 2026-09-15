package com.tareas.app.admin.dto;

import lombok.Getter;

@Getter
public class AdminRefreshResponseDTO {

    private final String token;
    private final String refreshToken;
    private final String type;

    public AdminRefreshResponseDTO(String token, String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.type = "Bearer";
    }
}
