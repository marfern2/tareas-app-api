package com.tareas.app.admin.security;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    private final AdminJwtService adminJwtService;
    private final AdminUserRepository adminUserRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7);

        try {
            String email = adminJwtService.extractUsername(jwt);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                AdminUser adminUser = adminUserRepository.findByEmail(email).orElse(null);

                if (adminUser != null && adminUser.getEnabled()) {
                    AdminUserDetails adminUserDetails = new AdminUserDetails(adminUser);

                    if (adminJwtService.isTokenValid(jwt, adminUserDetails.getUsername())) {
                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(
                                        adminUserDetails,
                                        null,
                                        adminUserDetails.getAuthorities()
                                );
                        authToken.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request)
                        );
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error procesando token JWT admin: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
