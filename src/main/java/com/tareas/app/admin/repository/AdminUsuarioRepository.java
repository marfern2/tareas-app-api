package com.tareas.app.admin.repository;

import com.tareas.app.model.Usuario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminUsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUsername(String username);

    Optional<Usuario> findByEmail(String email);

    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.email AS email,
                   u.enabled AS enabled,
                   COUNT(t) AS taskCount,
                   COUNT(DISTINCT t.tipoTarea.id) AS taskTypeCount
            FROM Usuario u
            LEFT JOIN Tarea t ON t.usuario = u
            GROUP BY u.id, u.username, u.email, u.enabled
            """)
    Page<UserTaskAggregation> findAllWithTaskCounts(Pageable pageable);

    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.email AS email,
                   u.enabled AS enabled,
                   COUNT(t) AS taskCount,
                   COUNT(DISTINCT t.tipoTarea.id) AS taskTypeCount
            FROM Usuario u
            LEFT JOIN Tarea t ON t.usuario = u
            WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
            GROUP BY u.id, u.username, u.email, u.enabled
            """)
    Page<UserTaskAggregation> searchWithTaskCounts(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.email AS email,
                   u.enabled AS enabled,
                   COUNT(t) AS taskCount,
                   COUNT(DISTINCT t.tipoTarea.id) AS taskTypeCount
            FROM Usuario u
            LEFT JOIN Tarea t ON t.usuario = u
            WHERE u.id = :id
            GROUP BY u.id, u.username, u.email, u.enabled
            """)
    Optional<UserTaskAggregation> findByIdWithTaskCounts(@Param("id") Long id);

    interface UserTaskAggregation {
        Long getId();
        String getUsername();
        String getEmail();
        Boolean getEnabled();
        long getTaskCount();
        long getTaskTypeCount();
    }
}
