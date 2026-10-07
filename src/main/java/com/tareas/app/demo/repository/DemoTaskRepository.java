package com.tareas.app.demo.repository;

import com.tareas.app.demo.model.DemoTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface DemoTaskRepository extends JpaRepository<DemoTask, Long>, JpaSpecificationExecutor<DemoTask> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from DemoTask t where t.id = :id")
    Optional<DemoTask> lockById(Long id);
    boolean existsByDemoUserIdAndPublicationStatus(Long userId, com.tareas.app.demo.model.PublicationStatus status);
    boolean existsByDemoTaskTypeIdAndPublicationStatus(Long typeId, com.tareas.app.demo.model.PublicationStatus status);
    boolean existsByDemoTaskTypeId(Long typeId);
    long countByPublicationStatus(com.tareas.app.demo.model.PublicationStatus status);
    long countByCompletedTrue();
    Optional<DemoTask> findByPublicId(UUID publicId);
}
