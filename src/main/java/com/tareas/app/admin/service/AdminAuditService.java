package com.tareas.app.admin.service;

import com.tareas.app.admin.model.AdminAuditEvent;
import com.tareas.app.admin.repository.AdminAuditEventRepository;
import com.tareas.app.admin.security.AdminUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final AdminAuditEventRepository repository;

    /** Called within the mutation transaction. An audit write failure rolls it back. */
    public void success(String operation, String resourceType, Long resourceId) {
        save(operation, resourceType, resourceId, "SUCCESS", null, null, null);
    }
    public void fixtureRestoreSuccess(long previous, long next, String counts) {
        save("DEMO_FIXTURE_RESTORE", "DEMO_CATALOG", 1L, "SUCCESS", previous, next, counts);
    }

    /** Failed requests are recorded independently after the business transaction rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String operation, String resourceType, Long resourceId) {
        save(operation, resourceType, resourceId, "FAILURE", null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fixtureRestoreFailure(Long previous, String counts) {
        save("DEMO_FIXTURE_RESTORE", "DEMO_CATALOG", 1L, "FAILURE", previous, previous, counts);
    }

    private void save(String operation, String resourceType, Long resourceId, String outcome,
                      Long previous, Long next, String counts) {
        AdminAuditEvent event = new AdminAuditEvent();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminUserDetails details) {
            event.setAdminUserId(details.getAdminUser().getId());
        }
        event.setOperation(operation);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setOutcome(outcome);
        event.setOccurredAt(Instant.now());
        event.setPreviousRevision(previous);
        event.setNewRevision(next);
        event.setRestoreCounts(counts);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String correlation = request.getHeader("X-Request-ID");
            if (correlation != null && correlation.length() <= 100 && correlation.matches("[A-Za-z0-9._-]+")) {
                event.setCorrelationId(correlation);
            }
        }
        if ("DEMO_FIXTURE_RESTORE".equals(operation) && event.getCorrelationId() == null)
            event.setCorrelationId(UUID.randomUUID().toString());
        repository.saveAndFlush(event);
    }
}
