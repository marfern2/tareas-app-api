package com.tareas.app.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDetailDTO {
    private Long id;
    private String username;
    private String email;
    private boolean enabled;
    private long totalTasks;
    private long completedTasks;
    private long pendingTasks;
    private long taskTypeCount;
}
