package com.tareas.app.admin.controller;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminSetUserEnabledRequest;
import com.tareas.app.admin.dto.AdminTaskTypeSummaryDTO;
import com.tareas.app.admin.dto.AdminUpdateUserRequest;
import com.tareas.app.admin.dto.AdminUserDetailDTO;
import com.tareas.app.admin.dto.AdminUserSummaryDTO;
import com.tareas.app.admin.dto.AdminUserTaskSummaryDTO;
import com.tareas.app.admin.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<AdminPageDTO<AdminUserSummaryDTO>> listarUsuarios(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        log.info("Admin request: listar usuarios - search={}, page={}, size={}, sort={}", search, page, size, sort);
        return ResponseEntity.ok(adminUserService.listarUsuarios(search, page, size, sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailDTO> obtenerDetalle(@PathVariable Long id) {
        log.info("Admin request: detalle usuario ID={}", id);
        return ResponseEntity.ok(adminUserService.obtenerDetalle(id));
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<AdminPageDTO<AdminUserTaskSummaryDTO>> listarTareasUsuario(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Admin request: tareas del usuario ID={} - page={}, size={}", id, page, size);
        return ResponseEntity.ok(adminUserService.listarTareasUsuario(id, page, size));
    }

    @GetMapping("/{id}/task-types")
    public ResponseEntity<AdminPageDTO<AdminTaskTypeSummaryDTO>> listarTiposUsuario(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        log.info("Admin request: tipos de tarea del usuario ID={} - page={}, size={}, sort={}", id, page, size, sort);
        return ResponseEntity.ok(adminUserService.listarTiposUsuario(id, page, size, sort));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AdminUserDetailDTO> actualizarUsuario(
            @PathVariable Long id,
            @Valid @RequestBody AdminUpdateUserRequest request) {

        log.info("Admin request: actualizar usuario ID={}", id);
        return ResponseEntity.ok(adminUserService.actualizarUsuario(id, request));
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<AdminUserDetailDTO> actualizarEnabled(
            @PathVariable Long id,
            @RequestBody AdminSetUserEnabledRequest request) {

        log.info("Admin request: actualizar enabled usuario ID={} -> {}", id, request.isEnabled());
        return ResponseEntity.ok(adminUserService.actualizarEnabled(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarUsuario(@PathVariable Long id) {
        log.info("Admin request: eliminar usuario ID={}", id);
        adminUserService.eliminarUsuario(id);
        return ResponseEntity.noContent().build();
    }
}
