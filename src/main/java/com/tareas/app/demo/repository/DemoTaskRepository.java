package com.tareas.app.demo.repository;

import com.tareas.app.demo.model.DemoTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoTaskRepository extends JpaRepository<DemoTask, Long> {
    Optional<DemoTask> findByPublicId(UUID publicId);
}
