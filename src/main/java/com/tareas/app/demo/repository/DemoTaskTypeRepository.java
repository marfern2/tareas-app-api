package com.tareas.app.demo.repository;

import com.tareas.app.demo.model.DemoTaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface DemoTaskTypeRepository extends JpaRepository<DemoTaskType, Long>, JpaSpecificationExecutor<DemoTaskType> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from DemoTaskType t where t.id = :id")
    Optional<DemoTaskType> lockById(Long id);
    boolean existsByDemoUserIdAndPublicationStatus(Long userId, com.tareas.app.demo.model.PublicationStatus status);
    boolean existsByDemoUserId(Long userId);
    long countByPublicationStatus(com.tareas.app.demo.model.PublicationStatus status);
    Optional<DemoTaskType> findByPublicId(UUID publicId);
}
