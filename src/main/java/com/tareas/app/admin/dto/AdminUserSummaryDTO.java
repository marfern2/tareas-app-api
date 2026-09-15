package com.tareas.app.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserSummaryDTO {
    private Long id;
    private String username;
    private String email;
    private long taskCount;
    private long taskTypeCount;
}
