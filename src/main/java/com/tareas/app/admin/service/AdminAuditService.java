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

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final AdminAuditEventRepository repository;

    /** Called within the mutation transaction. An audit write failure rolls it back. */
    public void success(String operation, String resourceType, Long resourceId) {
        save(operation, resourceType, resourceId, "SUCCESS");
    }

    /** Failed requests are recorded independently after the business transaction rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String operation, String resourceType, Long resourceId) {
        save(operation, resourceType, resourceId, "FAILURE");
    }

    private void save(String operation, String resourceType, Long resourceId, String outcome) {
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
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String correlation = request.getHeader("X-Request-ID");
            if (correlation != null && correlation.length() <= 100 && correlation.matches("[A-Za-z0-9._-]+")) {
                event.setCorrelationId(correlation);
            }
        }
        repository.saveAndFlush(event);
    }
}
