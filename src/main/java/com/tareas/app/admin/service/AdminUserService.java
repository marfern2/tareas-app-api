package com.tareas.app.admin.service;

import com.tareas.app.admin.dto.AdminPageDTO;
import com.tareas.app.admin.dto.AdminSetUserEnabledRequest;
import com.tareas.app.admin.dto.AdminTaskTypeSummaryDTO;
import com.tareas.app.admin.dto.AdminUpdateUserRequest;
import com.tareas.app.admin.dto.AdminUserDetailDTO;
import com.tareas.app.admin.dto.AdminUserSummaryDTO;
import com.tareas.app.admin.dto.AdminUserTaskSummaryDTO;
import com.tareas.app.admin.repository.AdminTareaRepository;
import com.tareas.app.admin.repository.AdminTipoTareaRepository;
import com.tareas.app.admin.repository.AdminUsuarioRepository;
import com.tareas.app.exception.ResourceConflictException;
import com.tareas.app.exception.ResourceNotFoundException;
import com.tareas.app.exception.ValidacionException;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.RefreshTokenRepository;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminUsuarioRepository adminUsuarioRepository;
    private final AdminTareaRepository adminTareaRepository;
    private final AdminTipoTareaRepository adminTipoTareaRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TareaRepository tareaRepository;
    private final TipoTareaRepository tipoTareaRepository;

    private static final Set<String> ALLOWED_SORT_FIELDS_TASK_TYPES = Set.of("id", "nombre", "color");

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
                Boolean.TRUE.equals(agg.getEnabled()),
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

    @Transactional(readOnly = true)
    public AdminPageDTO<AdminTaskTypeSummaryDTO> listarTiposUsuario(Long usuarioId, int page, int size, String sort) {
        if (!adminUsuarioRepository.existsById(usuarioId)) {
            throw new ResourceNotFoundException("Usuario no encontrado");
        }

        Pageable pageable = buildPageableTaskTypes(page, size, sort);

        Page<AdminTipoTareaRepository.TaskTypeAggregation> aggregationPage =
                adminTipoTareaRepository.findByUsuarioIdWithTaskCounts(usuarioId, pageable);

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

    @Transactional
    public AdminUserDetailDTO actualizarUsuario(Long id, AdminUpdateUserRequest request) {
        boolean hasUsername = request.getUsername() != null && !request.getUsername().isBlank();
        boolean hasEmail = request.getEmail() != null && !request.getEmail().isBlank();

        if (!hasUsername && !hasEmail) {
            throw new ValidacionException("body", "El body no puede estar vacío");
        }

        Usuario usuario = adminUsuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        if (request.getUsername() != null && request.getUsername().isBlank()) {
            throw new ValidacionException("username", "El username no puede estar vacío");
        }

        if (request.getEmail() != null && request.getEmail().isBlank()) {
            throw new ValidacionException("email", "El email no puede estar vacío");
        }

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            adminUsuarioRepository.findByUsername(request.getUsername().trim())
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            throw new ResourceConflictException("El username ya está en uso por otro usuario");
                        }
                    });
            usuario.setUsername(request.getUsername().trim());
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            adminUsuarioRepository.findByEmail(request.getEmail().trim())
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            throw new ResourceConflictException("El email ya está en uso por otro usuario");
                        }
                    });
            usuario.setEmail(request.getEmail().trim());
        }

        adminUsuarioRepository.save(usuario);

        long totalTasks = adminTareaRepository.countByUsuarioId(id);
        long completedTasks = adminTareaRepository.countCompletedByUsuarioId(id);

        return new AdminUserDetailDTO(
                usuario.getId(),
                usuario.getUsername(),
                usuario.getEmail(),
                Boolean.TRUE.equals(usuario.getEnabled()),
                totalTasks,
                completedTasks,
                totalTasks - completedTasks,
                adminTipoTareaRepository.countByUsuarioId(id)
        );
    }

    @Transactional
    public AdminUserDetailDTO actualizarEnabled(Long id, AdminSetUserEnabledRequest request) {
        Usuario usuario = adminUsuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        boolean wasEnabled = Boolean.TRUE.equals(usuario.getEnabled());
        usuario.setEnabled(request.isEnabled());
        adminUsuarioRepository.save(usuario);

        if (wasEnabled && !request.isEnabled()) {
            revocarRefreshTokensPorUsuario(id);
            log.info("Usuario {} deshabilitado, refresh tokens revocados", id);
        }

        long totalTasks = adminTareaRepository.countByUsuarioId(id);
        long completedTasks = adminTareaRepository.countCompletedByUsuarioId(id);

        return new AdminUserDetailDTO(
                usuario.getId(),
                usuario.getUsername(),
                usuario.getEmail(),
                Boolean.TRUE.equals(usuario.getEnabled()),
                totalTasks,
                completedTasks,
                totalTasks - completedTasks,
                adminTipoTareaRepository.countByUsuarioId(id)
        );
    }

    @Transactional
    public void eliminarUsuario(Long id) {
        Usuario usuario = adminUsuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        List<Tarea> tareas = tareaRepository.findByUsuarioId(id);
        if (!tareas.isEmpty()) {
            tareaRepository.deleteAll(tareas);
        }

        List<TipoTarea> tipos = tipoTareaRepository.findByUsuarioId(id);
        if (!tipos.isEmpty()) {
            tipoTareaRepository.deleteAll(tipos);
        }

        refreshTokenRepository.eliminarPorUsuario(id);

        adminUsuarioRepository.delete(usuario);
        log.info("Usuario {} eliminado junto con {} tareas y {} tipos de tarea", id, tareas.size(), tipos.size());
    }

    private void revocarRefreshTokensPorUsuario(Long usuarioId) {
        refreshTokenRepository.revocarTodosPorUsuario(usuarioId, LocalDateTime.now());
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

    private Pageable buildPageableTaskTypes(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!ALLOWED_SORT_FIELDS_TASK_TYPES.contains(field)) {
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
