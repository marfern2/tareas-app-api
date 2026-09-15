package com.tareas.app.admin.repository;

import com.tareas.app.model.Usuario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminUsuarioRepository extends JpaRepository<Usuario, Long> {

    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.email AS email,
                   COUNT(t) AS taskCount,
                   COUNT(DISTINCT t.tipoTarea.id) AS taskTypeCount
            FROM Usuario u
            LEFT JOIN Tarea t ON t.usuario = u
            GROUP BY u.id, u.username, u.email
            """)
    Page<UserTaskAggregation> findAllWithTaskCounts(Pageable pageable);

    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.email AS email,
                   COUNT(t) AS taskCount,
                   COUNT(DISTINCT t.tipoTarea.id) AS taskTypeCount
            FROM Usuario u
            LEFT JOIN Tarea t ON t.usuario = u
            WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
            GROUP BY u.id, u.username, u.email
            """)
    Page<UserTaskAggregation> searchWithTaskCounts(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.email AS email,
                   COUNT(t) AS taskCount,
                   COUNT(DISTINCT t.tipoTarea.id) AS taskTypeCount
            FROM Usuario u
            LEFT JOIN Tarea t ON t.usuario = u
            WHERE u.id = :id
            GROUP BY u.id, u.username, u.email
            """)
    Optional<UserTaskAggregation> findByIdWithTaskCounts(@Param("id") Long id);

    interface UserTaskAggregation {
        Long getId();
        String getUsername();
        String getEmail();
        long getTaskCount();
        long getTaskTypeCount();
    }
}
