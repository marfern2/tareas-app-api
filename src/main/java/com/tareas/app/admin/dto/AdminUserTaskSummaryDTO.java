package com.tareas.app.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserTaskSummaryDTO {
    private Long id;
    private String titulo;
    private String descripcion;
    private LocalDate fecha;
    private Boolean completada;
    private Integer urgencia;
    private Long tipoTareaId;
    private String tipoTareaNombre;
    private String tipoTareaColor;
}
