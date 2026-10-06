package com.tareas.app.admin.security;

import com.tareas.app.security.JsonResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class AdminRateLimitFilter extends OncePerRequestFilter {
    private final AdminRateLimitService limits;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        AdminRateLimitService.Decision decision;
        if ("/api/admin/auth/login".equals(path)) {
            decision = limits.allowLoginGlobal();
        } else if ("/api/admin/auth/refresh".equals(path) || "/api/admin/auth/logout".equals(path)) {
            decision = limits.allowRefreshGlobal();
        } else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof AdminUserDetails details)) {
                chain.doFilter(request, response);
                return;
            }
            Long id = details.getAdminUser().getId();
            decision = switch (request.getMethod()) {
                case "GET", "HEAD" -> limits.allowRead(id);
                case "DELETE" -> limits.allowDelete(id);
                default -> limits.allowWrite(id);
            };
        }
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(JsonResponses.body(429, "Too Many Requests", "Demasiadas solicitudes, inténtalo más tarde"));
            return;
        }
        chain.doFilter(request, response);
    }
}
