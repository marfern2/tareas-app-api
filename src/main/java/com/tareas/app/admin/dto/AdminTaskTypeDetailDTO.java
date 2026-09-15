package com.tareas.app.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminTaskTypeDetailDTO {
    private Long id;
    private String nombre;
    private String descripcion;
    private String color;
    private Long usuarioId;
    private String usuarioUsername;
    private String usuarioEmail;
    private long taskCount;
}
