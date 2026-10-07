package com.tareas.app.demo.repository;

import com.tareas.app.demo.model.DemoTaskType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoTaskTypeRepository extends JpaRepository<DemoTaskType, Long> {
    Optional<DemoTaskType> findByPublicId(UUID publicId);
}
