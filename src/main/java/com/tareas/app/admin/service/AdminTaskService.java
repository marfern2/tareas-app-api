package com.tareas.app.admin.service;

import com.tareas.app.admin.dto.AdminCreateTaskRequest;
import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminTaskDetailDTO;
import com.tareas.app.admin.dto.AdminTaskSummaryDTO;
import com.tareas.app.admin.dto.AdminUpdateTaskRequest;
import com.tareas.app.admin.repository.AdminTipoTareaRepository;
import com.tareas.app.admin.repository.AdminTareaRepository;
import com.tareas.app.admin.repository.AdminUsuarioRepository;
import com.tareas.app.exception.ResourceNotFoundException;
import com.tareas.app.exception.ValidacionException;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
import com.tareas.app.model.Usuario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTaskService {

    private final AdminTareaRepository adminTareaRepository;
    private final AdminUsuarioRepository adminUsuarioRepository;
    private final AdminTipoTareaRepository adminTipoTareaRepository;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "titulo", "fecha", "completada", "urgencia");
    private static final int MAX_PAGE_SIZE = 100;

    @Transactional(readOnly = true)
    public AdminPageDTO<AdminTaskSummaryDTO> listarTareas(
            String search, Long userId, Boolean completed, Integer urgency,
            Long taskTypeId, int page, int size, String sort) {

        Pageable pageable = buildPageable(page, size, sort);
        Page<Tarea> tareaPage = adminTareaRepository.findGlobalWithFilters(
                search != null ? search.trim() : null,
                userId, completed, urgency, taskTypeId, pageable);

        Page<AdminTaskSummaryDTO> dtoPage = tareaPage.map(t -> new AdminTaskSummaryDTO(
                t.getId(),
                t.getTitulo(),
                t.getDescripcion(),
                t.getFecha(),
                t.getCompletada(),
                t.getUrgencia(),
                t.getUsuario().getId(),
                t.getUsuario().getUsername(),
                t.getUsuario().getEmail(),
                t.getTipoTarea().getId(),
                t.getTipoTarea().getNombre(),
                t.getTipoTarea().getColor()
        ));

        return new AdminPageDTO<>(dtoPage);
    }

    @Transactional(readOnly = true)
    public AdminTaskDetailDTO obtenerDetalle(Long id) {
        Tarea tarea = adminTareaRepository.findByIdGlobal(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));

        return new AdminTaskDetailDTO(
                tarea.getId(),
                tarea.getTitulo(),
                tarea.getDescripcion(),
                tarea.getFecha(),
                tarea.getCompletada(),
                tarea.getUrgencia(),
                tarea.getUsuario().getId(),
                tarea.getUsuario().getUsername(),
                tarea.getUsuario().getEmail(),
                tarea.getTipoTarea().getId(),
                tarea.getTipoTarea().getNombre(),
                tarea.getTipoTarea().getColor()
        );
    }

    @Transactional
    public AdminTaskDetailDTO crearTarea(Long usuarioId, AdminCreateTaskRequest request) {
        Usuario usuario = adminUsuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        TipoTarea tipoTarea = adminTipoTareaRepository.findByIdAndUsuarioId(request.getTipoTareaId(), usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Tipo de tarea no encontrado para este usuario"));

        Tarea tarea = Tarea.builder()
                .titulo(request.getTitulo().trim())
                .descripcion(request.getDescripcion() != null ? request.getDescripcion().trim() : null)
                .fecha(request.getFecha())
                .completada(Boolean.TRUE.equals(request.getCompletada()) ? true : false)
                .urgencia(request.getUrgencia() != null ? request.getUrgencia() : 0)
                .usuario(usuario)
                .tipoTarea(tipoTarea)
                .build();

        Tarea tareaGuardada = adminTareaRepository.save(tarea);
        log.info("Admin creó tarea ID={} para usuario ID={}", tareaGuardada.getId(), usuarioId);

        return toDetailDTO(tareaGuardada);
    }

    @Transactional
    public AdminTaskDetailDTO actualizarTarea(Long usuarioId, Long tareaId, AdminUpdateTaskRequest request) {
        Usuario usuario = adminUsuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        Tarea tarea = adminTareaRepository.findByIdAndUsuarioId(tareaId, usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada para este usuario"));

        boolean hasAnyField = request.getTitulo() != null
                || request.getDescripcion() != null
                || request.getFecha() != null
                || request.getCompletada() != null
                || request.getUrgencia() != null
                || request.getTipoTareaId() != null;

        if (!hasAnyField) {
            throw new ValidacionException("body", "El body no puede estar vacío");
        }

        if (request.getTitulo() != null) {
            if (request.getTitulo().isBlank()) {
                throw new ValidacionException("titulo", "El título no puede estar vacío");
            }
            tarea.setTitulo(request.getTitulo().trim());
        }

        if (request.getDescripcion() != null) {
            tarea.setDescripcion(request.getDescripcion().trim());
        }

        if (request.getFecha() != null) {
            tarea.setFecha(request.getFecha());
        }

        if (request.getCompletada() != null) {
            tarea.setCompletada(request.getCompletada());
        }

        if (request.getUrgencia() != null) {
            tarea.setUrgencia(request.getUrgencia());
        }

        if (request.getTipoTareaId() != null) {
            TipoTarea tipoTarea = adminTipoTareaRepository.findByIdAndUsuarioId(request.getTipoTareaId(), usuarioId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tipo de tarea no encontrado para este usuario"));
            tarea.setTipoTarea(tipoTarea);
        }

        Tarea tareaActualizada = adminTareaRepository.save(tarea);
        log.info("Admin actualizó tarea ID={} del usuario ID={}", tareaId, usuarioId);

        return toDetailDTO(tareaActualizada);
    }

    @Transactional
    public void eliminarTarea(Long usuarioId, Long tareaId) {
        if (!adminUsuarioRepository.existsById(usuarioId)) {
            throw new ResourceNotFoundException("Usuario no encontrado");
        }

        Tarea tarea = adminTareaRepository.findByIdAndUsuarioId(tareaId, usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada para este usuario"));

        adminTareaRepository.delete(tarea);
        log.info("Admin eliminó tarea ID={} del usuario ID={}", tareaId, usuarioId);
    }

    private AdminTaskDetailDTO toDetailDTO(Tarea t) {
        return new AdminTaskDetailDTO(
                t.getId(),
                t.getTitulo(),
                t.getDescripcion(),
                t.getFecha(),
                t.getCompletada(),
                t.getUrgencia(),
                t.getUsuario().getId(),
                t.getUsuario().getUsername(),
                t.getUsuario().getEmail(),
                t.getTipoTarea().getId(),
                t.getTipoTarea().getNombre(),
                t.getTipoTarea().getColor()
        );
    }

    private Pageable buildPageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!ALLOWED_SORT_FIELDS.contains(field)) {
                field = "fecha";
            }
            Sort.Direction direction = parts.length > 1
                    && parts[1].trim().equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            return PageRequest.of(safePage, safeSize, Sort.by(direction, field));
        }

        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "fecha"));
    }
}
