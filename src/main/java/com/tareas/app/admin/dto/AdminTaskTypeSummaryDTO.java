package com.tareas.app.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminTaskTypeSummaryDTO {
    private Long id;
    private String nombre;
    private String descripcion;
    private String color;
    private Long usuarioId;
    private String usuarioUsername;
    private long taskCount;
}
