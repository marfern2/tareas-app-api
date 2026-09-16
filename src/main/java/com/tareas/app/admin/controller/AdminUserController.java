package com.tareas.app.admin.controller;

import com.tareas.app.admin.dto.AdminCreateTaskRequest;
import com.tareas.app.admin.dto.AdminCreateTaskTypeRequest;
import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminSetUserEnabledRequest;
import com.tareas.app.admin.dto.AdminTaskDetailDTO;
import com.tareas.app.admin.dto.AdminTaskTypeDetailDTO;
import com.tareas.app.admin.dto.AdminTaskTypeSummaryDTO;
import com.tareas.app.admin.dto.AdminUpdateTaskRequest;
import com.tareas.app.admin.dto.AdminUpdateTaskTypeRequest;
import com.tareas.app.admin.dto.AdminUpdateUserRequest;
import com.tareas.app.admin.dto.AdminUserDetailDTO;
import com.tareas.app.admin.dto.AdminUserSummaryDTO;
import com.tareas.app.admin.dto.AdminUserTaskSummaryDTO;
import com.tareas.app.admin.service.AdminTaskService;
import com.tareas.app.admin.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AdminTaskService adminTaskService;

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

    // ========================================================================
    // CRUD de tareas del usuario
    // ========================================================================

    @PostMapping("/{id}/tasks")
    public ResponseEntity<AdminTaskDetailDTO> crearTarea(
            @PathVariable Long id,
            @Valid @RequestBody AdminCreateTaskRequest request) {

        log.info("Admin request: crear tarea para usuario ID={}", id);
        AdminTaskDetailDTO tarea = adminTaskService.crearTarea(id, request);
        return new ResponseEntity<>(tarea, HttpStatus.CREATED);
    }

    @PatchMapping("/{id}/tasks/{taskId}")
    public ResponseEntity<AdminTaskDetailDTO> actualizarTarea(
            @PathVariable Long id,
            @PathVariable Long taskId,
            @Valid @RequestBody AdminUpdateTaskRequest request) {

        log.info("Admin request: actualizar tarea ID={} del usuario ID={}", taskId, id);
        return ResponseEntity.ok(adminTaskService.actualizarTarea(id, taskId, request));
    }

    @DeleteMapping("/{id}/tasks/{taskId}")
    public ResponseEntity<Void> eliminarTarea(
            @PathVariable Long id,
            @PathVariable Long taskId) {

        log.info("Admin request: eliminar tarea ID={} del usuario ID={}", taskId, id);
        adminTaskService.eliminarTarea(id, taskId);
        return ResponseEntity.noContent().build();
    }

    // ========================================================================
    // CRUD de tipos de tarea del usuario
    // ========================================================================

    @PostMapping("/{id}/task-types")
    public ResponseEntity<AdminTaskTypeDetailDTO> crearTipo(
            @PathVariable Long id,
            @Valid @RequestBody AdminCreateTaskTypeRequest request) {

        log.info("Admin request: crear tipo de tarea para usuario ID={}", id);
        AdminTaskTypeDetailDTO tipo = adminUserService.crearTipo(id, request);
        return new ResponseEntity<>(tipo, HttpStatus.CREATED);
    }

    @PatchMapping("/{id}/task-types/{taskTypeId}")
    public ResponseEntity<AdminTaskTypeDetailDTO> actualizarTipo(
            @PathVariable Long id,
            @PathVariable Long taskTypeId,
            @Valid @RequestBody AdminUpdateTaskTypeRequest request) {

        log.info("Admin request: actualizar tipo de tarea ID={} del usuario ID={}", taskTypeId, id);
        return ResponseEntity.ok(adminUserService.actualizarTipo(id, taskTypeId, request));
    }

    @DeleteMapping("/{id}/task-types/{taskTypeId}")
    public ResponseEntity<Void> eliminarTipo(
            @PathVariable Long id,
            @PathVariable Long taskTypeId) {

        log.info("Admin request: eliminar tipo de tarea ID={} del usuario ID={}", taskTypeId, id);
        adminUserService.eliminarTipo(id, taskTypeId);
        return ResponseEntity.noContent().build();
    }
}
