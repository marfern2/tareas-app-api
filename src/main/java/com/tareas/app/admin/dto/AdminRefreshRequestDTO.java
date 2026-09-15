package com.tareas.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminRefreshRequestDTO {

    @NotBlank(message = "El refreshToken es obligatorio")
    private String refreshToken;
}
