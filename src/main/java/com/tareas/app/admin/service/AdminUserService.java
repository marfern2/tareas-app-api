package com.tareas.app.admin.service;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminUserDetailDTO;
import com.tareas.app.admin.dto.AdminUserSummaryDTO;
import com.tareas.app.admin.dto.AdminUserTaskSummaryDTO;
import com.tareas.app.admin.repository.AdminTareaRepository;
import com.tareas.app.admin.repository.AdminUsuarioRepository;
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
public class AdminUserService {

    private final AdminUsuarioRepository adminUsuarioRepository;
    private final AdminTareaRepository adminTareaRepository;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "username", "email");
    private static final int MAX_PAGE_SIZE = 100;

    @Transactional(readOnly = true)
    public AdminPageDTO<AdminUserSummaryDTO> listarUsuarios(String search, int page, int size, String sort) {
        Pageable pageable = buildPageable(page, size, sort);
        Page<AdminUsuarioRepository.UserTaskAggregation> aggregationPage;

        if (search != null && !search.isBlank()) {
            aggregationPage = adminUsuarioRepository.searchWithTaskCounts(search.trim(), pageable);
        } else {
            aggregationPage = adminUsuarioRepository.findAllWithTaskCounts(pageable);
        }

        Page<AdminUserSummaryDTO> dtoPage = aggregationPage.map(agg ->
                new AdminUserSummaryDTO(
                        agg.getId(),
                        agg.getUsername(),
                        agg.getEmail(),
                        agg.getTaskCount(),
                        agg.getTaskTypeCount()
                )
        );

        return new AdminPageDTO<>(dtoPage);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDTO obtenerDetalle(Long id) {
        AdminUsuarioRepository.UserTaskAggregation agg = adminUsuarioRepository.findByIdWithTaskCounts(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        long totalTasks = agg.getTaskCount();
        long completedTasks = adminTareaRepository.countCompletedByUsuarioId(id);

        return new AdminUserDetailDTO(
                agg.getId(),
                agg.getUsername(),
                agg.getEmail(),
                totalTasks,
                completedTasks,
                totalTasks - completedTasks,
                agg.getTaskTypeCount()
        );
    }

    @Transactional(readOnly = true)
    public AdminPageDTO<AdminUserTaskSummaryDTO> listarTareasUsuario(Long usuarioId, int page, int size) {
        if (!adminUsuarioRepository.existsById(usuarioId)) {
            throw new ResourceNotFoundException("Usuario no encontrado");
        }

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "fecha"));

        Page<Tarea> tareaPage = adminTareaRepository.findByUsuarioIdOrderByFechaDesc(usuarioId, pageable);

        Page<AdminUserTaskSummaryDTO> dtoPage = tareaPage.map(t -> new AdminUserTaskSummaryDTO(
                t.getId(),
                t.getTitulo(),
                t.getDescripcion(),
                t.getFecha(),
                t.getCompletada(),
                t.getUrgencia(),
                t.getTipoTarea() != null ? t.getTipoTarea().getId() : null,
                t.getTipoTarea() != null ? t.getTipoTarea().getNombre() : null,
                t.getTipoTarea() != null ? t.getTipoTarea().getColor() : null
        ));

        return new AdminPageDTO<>(dtoPage);
    }

    private Pageable buildPageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!ALLOWED_SORT_FIELDS.contains(field)) {
                field = "id";
            }
            Sort.Direction direction = parts.length > 1
                    && parts[1].trim().equalsIgnoreCase("desc")
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            return PageRequest.of(safePage, safeSize, Sort.by(direction, field));
        }

        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "id"));
    }
}
