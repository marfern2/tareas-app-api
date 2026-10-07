package com.tareas.app.demo.repository;

import com.tareas.app.demo.model.DemoUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface DemoUserRepository extends JpaRepository<DemoUser, Long>, JpaSpecificationExecutor<DemoUser> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from DemoUser u where u.id = :id")
    Optional<DemoUser> lockById(Long id);
    boolean existsByHandleAndIdNot(String handle, Long id);
    boolean existsByHandle(String handle);
    long countByPublicationStatus(com.tareas.app.demo.model.PublicationStatus status);
    Optional<DemoUser> findByPublicId(UUID publicId);
    Optional<DemoUser> findByHandle(String handle);
}
