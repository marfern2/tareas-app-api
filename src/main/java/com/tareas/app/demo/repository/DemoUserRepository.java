package com.tareas.app.demo.repository;

import com.tareas.app.demo.model.DemoUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoUserRepository extends JpaRepository<DemoUser, Long> {
    Optional<DemoUser> findByPublicId(UUID publicId);
    Optional<DemoUser> findByHandle(String handle);
}
