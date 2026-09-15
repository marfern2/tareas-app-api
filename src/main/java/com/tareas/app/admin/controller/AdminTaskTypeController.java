package com.tareas.app.admin.controller;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminTaskTypeDetailDTO;
import com.tareas.app.admin.dto.AdminTaskTypeSummaryDTO;
import com.tareas.app.admin.service.AdminTaskTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/task-types")
@RequiredArgsConstructor
public class AdminTaskTypeController {

    private final AdminTaskTypeService adminTaskTypeService;

    @GetMapping
    public ResponseEntity<AdminPageDTO<AdminTaskTypeSummaryDTO>> listarTipos(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        log.info("Admin request: listar tipos de tarea - search={}, page={}, size={}, sort={}",
                search, page, size, sort);
        return ResponseEntity.ok(adminTaskTypeService.listarTipos(search, page, size, sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminTaskTypeDetailDTO> obtenerDetalle(@PathVariable Long id) {
        log.info("Admin request: detalle tipo de tarea ID={}", id);
        return ResponseEntity.ok(adminTaskTypeService.obtenerDetalle(id));
    }
}
