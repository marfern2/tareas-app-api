package com.tareas.app.admin.repository;

import com.tareas.app.model.Tarea;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminTareaRepository extends JpaRepository<Tarea, Long> {

    Page<Tarea> findByUsuarioIdOrderByFechaDesc(Long usuarioId, Pageable pageable);

    @Query("SELECT COUNT(t) FROM Tarea t WHERE t.usuario.id = :usuarioId")
    long countByUsuarioId(@Param("usuarioId") Long usuarioId);

    @Query("SELECT COUNT(t) FROM Tarea t WHERE t.usuario.id = :usuarioId AND t.completada = true")
    long countCompletedByUsuarioId(@Param("usuarioId") Long usuarioId);

    @Query(value = """
            SELECT t FROM Tarea t
            JOIN FETCH t.usuario u
            JOIN FETCH t.tipoTarea tt
            WHERE (:search IS NULL OR LOWER(t.titulo) LIKE LOWER(CONCAT('%', :search, '%'))
                                  OR LOWER(t.descripcion) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:userId IS NULL OR t.usuario.id = :userId)
            AND (:completed IS NULL
                 OR (:completed = true AND t.completada = true)
                 OR (:completed = false AND (t.completada = false OR t.completada IS NULL)))
            AND (:urgency IS NULL OR t.urgencia = :urgency)
            AND (:taskTypeId IS NULL OR t.tipoTarea.id = :taskTypeId)
            """,
            countQuery = """
            SELECT COUNT(t) FROM Tarea t
            WHERE (:search IS NULL OR LOWER(t.titulo) LIKE LOWER(CONCAT('%', :search, '%'))
                                  OR LOWER(t.descripcion) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:userId IS NULL OR t.usuario.id = :userId)
            AND (:completed IS NULL
                 OR (:completed = true AND t.completada = true)
                 OR (:completed = false AND (t.completada = false OR t.completada IS NULL)))
            AND (:urgency IS NULL OR t.urgencia = :urgency)
            AND (:taskTypeId IS NULL OR t.tipoTarea.id = :taskTypeId)
            """)
    Page<Tarea> findGlobalWithFilters(
            @Param("search") String search,
            @Param("userId") Long userId,
            @Param("completed") Boolean completed,
            @Param("urgency") Integer urgency,
            @Param("taskTypeId") Long taskTypeId,
            Pageable pageable);

    @Query("SELECT t FROM Tarea t JOIN FETCH t.usuario JOIN FETCH t.tipoTarea WHERE t.id = :id")
    java.util.Optional<Tarea> findByIdGlobal(@Param("id") Long id);

    @Query("SELECT t FROM Tarea t JOIN FETCH t.usuario u JOIN FETCH t.tipoTarea tt WHERE t.id = :id AND u.id = :usuarioId")
    java.util.Optional<Tarea> findByIdAndUsuarioId(@Param("id") Long id, @Param("usuarioId") Long usuarioId);

    @Query("SELECT COUNT(t) FROM Tarea t WHERE t.usuario.id = :usuarioId AND t.tipoTarea.id = :tipoTareaId")
    long countByUsuarioIdAndTipoTareaId(@Param("usuarioId") Long usuarioId, @Param("tipoTareaId") Long tipoTareaId);
}
