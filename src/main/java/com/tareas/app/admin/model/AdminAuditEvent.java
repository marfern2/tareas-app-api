package com.tareas.app.admin.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "admin_audit_events")
@Getter
@Setter
public class AdminAuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "admin_user_id")
    private Long adminUserId;
    @Column(nullable = false, length = 64)
    private String operation;
    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;
    @Column(name = "resource_id")
    private Long resourceId;
    @Column(nullable = false, length = 16)
    private String outcome;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "correlation_id", length = 100)
    private String correlationId;
    @Column(name = "previous_revision")
    private Long previousRevision;
    @Column(name = "new_revision")
    private Long newRevision;
    @Column(name = "restore_counts", length = 160)
    private String restoreCounts;
}
