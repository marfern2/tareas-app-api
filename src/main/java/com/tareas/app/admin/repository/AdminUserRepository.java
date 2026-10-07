package com.tareas.app.admin.repository;

import com.tareas.app.admin.model.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByEmail(String email);

    Optional<AdminUser> findByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    // Lock the account before locking its refresh token. This is also the order
    // used by the PostgreSQL revocation trigger when an account is disabled.
    @Query(value = "SELECT enabled FROM admin_users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Boolean> lockEnabledForSession(@Param("id") Long id);
}
