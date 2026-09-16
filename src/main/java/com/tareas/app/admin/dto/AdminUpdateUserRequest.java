package com.tareas.app.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUpdateUserRequest {

    @Size(min = 1, max = 255, message = "El username debe tener entre 1 y 255 caracteres")
    private String username;

    @Email(message = "El email debe tener formato válido")
    @Size(max = 254, message = "El email no puede exceder 254 caracteres")
    private String email;
}
