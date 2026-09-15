package com.tareas.app.admin.service;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminTaskDetailDTO;
import com.tareas.app.admin.dto.AdminTaskSummaryDTO;
import com.tareas.app.admin.repository.AdminTareaRepository;
import com.tareas.app.exception.ResourceNotFoundException;
import com.tareas.app.model.Tarea;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminTaskService {

    private final AdminTareaRepository adminTareaRepository;

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
