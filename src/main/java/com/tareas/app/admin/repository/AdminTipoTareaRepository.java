package com.tareas.app.admin.repository;

import com.tareas.app.model.TipoTarea;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminTipoTareaRepository extends JpaRepository<TipoTarea, Long> {

    @Query(value = """
            SELECT t.id AS id, t.nombre AS nombre, t.descripcion AS descripcion,
                   t.color AS color, u.id AS usuarioId, u.username AS usuarioUsername,
                   u.email AS usuarioEmail,
                   COUNT(task.id) AS taskCount
            FROM TipoTarea t
            LEFT JOIN Tarea task ON task.tipoTarea = t
            LEFT JOIN t.usuario u
            WHERE (:search IS NULL OR LOWER(t.nombre) LIKE LOWER(CONCAT('%', :search, '%'))
                                  OR LOWER(t.descripcion) LIKE LOWER(CONCAT('%', :search, '%')))
            GROUP BY t.id, t.nombre, t.descripcion, t.color, u.id, u.username, u.email
            """,
            countQuery = """
            SELECT COUNT(t) FROM TipoTarea t
            WHERE (:search IS NULL OR LOWER(t.nombre) LIKE LOWER(CONCAT('%', :search, '%'))
                                  OR LOWER(t.descripcion) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<TaskTypeAggregation> findGlobalWithTaskCounts(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT t.id AS id, t.nombre AS nombre, t.descripcion AS descripcion,
                   t.color AS color, u.id AS usuarioId, u.username AS usuarioUsername,
                   u.email AS usuarioEmail,
                   COUNT(task.id) AS taskCount
            FROM TipoTarea t
            LEFT JOIN Tarea task ON task.tipoTarea = t
            LEFT JOIN t.usuario u
            WHERE t.id = :id
            GROUP BY t.id, t.nombre, t.descripcion, t.color, u.id, u.username, u.email
            """)
    Optional<TaskTypeAggregation> findByIdWithTaskCount(@Param("id") Long id);

    @Query(value = """
            SELECT t.id AS id, t.nombre AS nombre, t.descripcion AS descripcion,
                   t.color AS color, u.id AS usuarioId, u.username AS usuarioUsername,
                   u.email AS usuarioEmail,
                   COUNT(task.id) AS taskCount
            FROM TipoTarea t
            LEFT JOIN Tarea task ON task.tipoTarea = t
            LEFT JOIN t.usuario u
            WHERE t.usuario.id = :usuarioId
            GROUP BY t.id, t.nombre, t.descripcion, t.color, u.id, u.username, u.email
            """,
            countQuery = """
            SELECT COUNT(t) FROM TipoTarea t
            WHERE t.usuario.id = :usuarioId
            """)
    Page<TaskTypeAggregation> findByUsuarioIdWithTaskCounts(@Param("usuarioId") Long usuarioId, Pageable pageable);

    interface TaskTypeAggregation {
        Long getId();
        String getNombre();
        String getDescripcion();
        String getColor();
        Long getUsuarioId();
        String getUsuarioUsername();
        String getUsuarioEmail();
        long getTaskCount();
    }
}
