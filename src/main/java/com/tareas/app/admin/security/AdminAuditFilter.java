package com.tareas.app.admin.security;

import com.tareas.app.admin.service.AdminAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class AdminAuditFilter extends OncePerRequestFilter {
    private final AdminAuditService audit;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean threw = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            threw = true;
            throw ex;
        } finally {
            if ((threw || response.getStatus() >= 400) && isMutation(request)) {
                String[] parts = request.getRequestURI().split("/");
                boolean demo = parts.length > 4 && "demo".equals(parts[3]);
                if (demo) {
                    if (parts.length > 5 && "fixtures".equals(parts[4]) && "restore".equals(parts[5])) {
                        try {
                            audit.fixtureRestoreFailure((Long) request.getAttribute("fixturePreviousRevision"),
                                    (String) request.getAttribute("fixtureCounts"));
                        } catch (RuntimeException ignored) {
                            log.error("No se pudo persistir la auditoría de restore fallido");
                        }
                    } else {
                        String type = switch (parts[4]) {
                            case "users" -> "DEMO_USER";
                            case "task-types" -> "DEMO_TASK_TYPE";
                            case "tasks" -> "DEMO_TASK";
                            default -> "UNKNOWN";
                        };
                        Long id = parts.length > 5 ? numeric(parts[5]) : null;
                        String action = parts.length > 6 && "publication".equals(parts[6]) ? "PUBLICATION" : switch (request.getMethod()) {
                            case "POST" -> "CREATE";
                            case "PATCH" -> "UPDATE";
                            case "DELETE" -> "DELETE";
                            default -> "UNKNOWN";
                        };
                        try {
                            audit.failure(type + "_" + action, type, id);
                        } catch (RuntimeException ignored) {
                            log.error("No se pudo persistir la auditoría de una solicitud demo fallida");
                        }
                    }
                } else {
                    String type = parts.length > 3 && "users".equals(parts[3]) ? "USER" : "UNKNOWN";
                    Long id = parts.length > 4 ? numeric(parts[4]) : null;
                    if (parts.length > 5 && "tasks".equals(parts[5])) {
                        type = "TASK";
                        id = parts.length > 6 ? numeric(parts[6]) : null;
                    } else if (parts.length > 5 && "task-types".equals(parts[5])) {
                        type = "TASK_TYPE";
                        id = parts.length > 6 ? numeric(parts[6]) : null;
                    }
                    try {
                        audit.failure(operation(request.getMethod(), type, parts), type, id);
                    } catch (RuntimeException ignored) {
                        log.error("No se pudo persistir la auditoría de una solicitud administrativa fallida");
                    }
                }
            }
        }
    }

    private boolean isMutation(HttpServletRequest request) {
        return (request.getRequestURI().startsWith("/api/admin/users/")
                || request.getRequestURI().matches("/api/admin/demo/(users|task-types|tasks)(/[^/]+(/publication)?)?")
                || request.getRequestURI().equals("/api/admin/demo/fixtures/restore"))
                && ("POST".equals(request.getMethod()) || "PATCH".equals(request.getMethod())
                || "DELETE".equals(request.getMethod()));
    }

    private Long numeric(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String operation(String method, String type, String[] parts) {
        if ("USER".equals(type)) {
            if (parts.length > 5 && "enabled".equals(parts[5])) return "USER_ENABLED_UPDATE";
            return "DELETE".equals(method) ? "USER_DELETE" : "USER_UPDATE";
        }
        String action = switch (method) {
            case "POST" -> "CREATE";
            case "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> "UNKNOWN";
        };
        return type + "_" + action;
    }
}
