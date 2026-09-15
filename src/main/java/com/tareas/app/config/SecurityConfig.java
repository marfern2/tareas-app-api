package com.tareas.app.config;

import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtAuthenticationFilter;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.security.JwtAuthenticationFilter;
import com.tareas.app.security.JwtService;
import com.tareas.app.security.JsonResponses;
import com.tareas.app.security.RateLimitFilter;
import com.tareas.app.security.RateLimitService;
import com.tareas.app.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(CustomUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(
            HttpSecurity http,
            AdminJwtService adminJwtService,
            AdminUserRepository adminUserRepository,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {

        AdminJwtAuthenticationFilter adminFilter = new AdminJwtAuthenticationFilter(adminJwtService, adminUserRepository);

        http
                .securityMatcher("/api/admin/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    JsonResponses.body(401, "Unauthorized", "No autenticado o token inválido"));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    JsonResponses.body(403, "Forbidden", "No tienes permisos para acceder a este recurso"));
                        })
                )
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/api/admin/auth/login",
                            "/api/admin/auth/refresh",
                            "/api/admin/auth/logout"
                    ).permitAll();
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(adminFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain normalFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            CustomUserDetailsService userDetailsService,
            RateLimitService rateLimitService
    ) throws Exception {

        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userDetailsService);
        RateLimitFilter rlFilter = new RateLimitFilter(rateLimitService);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    JsonResponses.body(401, "Unauthorized", "No autenticado o token inválido"));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    JsonResponses.body(403, "Forbidden", "No tienes permisos para acceder a este recurso"));
                        })
                )
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/auth/login",
                            "/auth/registro",
                            "/auth/refresh",
                            "/auth/logout",
                            "/error",
                            "/actuator/health",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/v3/api-docs/**"
                    ).permitAll();
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rlFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
