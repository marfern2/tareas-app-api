package com.tareas.app.admin.controller;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminTaskDetailDTO;
import com.tareas.app.admin.dto.AdminTaskSummaryDTO;
import com.tareas.app.admin.service.AdminTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/tasks")
@RequiredArgsConstructor
public class AdminTaskController {

    private final AdminTaskService adminTaskService;

    @GetMapping
    public ResponseEntity<AdminPageDTO<AdminTaskSummaryDTO>> listarTareas(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Boolean completed,
            @RequestParam(required = false) Integer urgency,
            @RequestParam(required = false) Long taskTypeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        log.info("Admin request: listar tareas - search={}, userId={}, completed={}, urgency={}, taskTypeId={}, page={}, size={}, sort={}",
                search, userId, completed, urgency, taskTypeId, page, size, sort);
        return ResponseEntity.ok(adminTaskService.listarTareas(
                search, userId, completed, urgency, taskTypeId, page, size, sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminTaskDetailDTO> obtenerDetalle(@PathVariable Long id) {
        log.info("Admin request: detalle tarea ID={}", id);
        return ResponseEntity.ok(adminTaskService.obtenerDetalle(id));
    }
}
