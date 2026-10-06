package com.tareas.app.admin.repository;

import com.tareas.app.admin.model.AdminAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditEventRepository extends JpaRepository<AdminAuditEvent, Long> {
}
