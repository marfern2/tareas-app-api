package com.tareas.app.admin.repository;

import com.tareas.app.model.Tarea;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminTareaRepository extends JpaRepository<Tarea, Long> {

    Page<Tarea> findByUsuarioIdOrderByFechaDesc(Long usuarioId, Pageable pageable);

    @Query("SELECT COUNT(t) FROM Tarea t WHERE t.usuario.id = :usuarioId AND t.completada = true")
    long countCompletedByUsuarioId(@Param("usuarioId") Long usuarioId);
}
