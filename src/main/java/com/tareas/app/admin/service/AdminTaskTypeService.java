package com.tareas.app.admin.service;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminTaskTypeDetailDTO;
import com.tareas.app.admin.dto.AdminTaskTypeSummaryDTO;
import com.tareas.app.admin.repository.AdminTipoTareaRepository;
import com.tareas.app.exception.ResourceNotFoundException;
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
public class AdminTaskTypeService {

    private final AdminTipoTareaRepository adminTipoTareaRepository;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "nombre", "color");
    private static final int MAX_PAGE_SIZE = 100;

    @Transactional(readOnly = true)
    public AdminPageDTO<AdminTaskTypeSummaryDTO> listarTipos(
            String search, int page, int size, String sort) {

        Pageable pageable = buildPageable(page, size, sort);
        Page<AdminTipoTareaRepository.TaskTypeAggregation> aggregationPage =
                adminTipoTareaRepository.findGlobalWithTaskCounts(
                        search != null ? search.trim() : null, pageable);

        Page<AdminTaskTypeSummaryDTO> dtoPage = aggregationPage.map(agg ->
                new AdminTaskTypeSummaryDTO(
                        agg.getId(),
                        agg.getNombre(),
                        agg.getDescripcion(),
                        agg.getColor(),
                        agg.getUsuarioId(),
                        agg.getUsuarioUsername(),
                        agg.getTaskCount()
                ));

        return new AdminPageDTO<>(dtoPage);
    }

    @Transactional(readOnly = true)
    public AdminTaskTypeDetailDTO obtenerDetalle(Long id) {
        AdminTipoTareaRepository.TaskTypeAggregation agg =
                adminTipoTareaRepository.findByIdWithTaskCount(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Tipo de tarea no encontrado"));

        return new AdminTaskTypeDetailDTO(
                agg.getId(),
                agg.getNombre(),
                agg.getDescripcion(),
                agg.getColor(),
                agg.getUsuarioId(),
                agg.getUsuarioUsername(),
                agg.getUsuarioEmail(),
                agg.getTaskCount()
        );
    }

    private Pageable buildPageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!ALLOWED_SORT_FIELDS.contains(field)) {
                field = "nombre";
            }
            Sort.Direction direction = parts.length > 1
                    && parts[1].trim().equalsIgnoreCase("desc")
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            return PageRequest.of(safePage, safeSize, Sort.by(direction, field));
        }

        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "nombre"));
    }
}
