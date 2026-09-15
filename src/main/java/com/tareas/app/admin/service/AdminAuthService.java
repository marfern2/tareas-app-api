package com.tareas.app.admin.service;

import com.tareas.app.admin.dto.AdminLoginDTO;
import com.tareas.app.admin.dto.AdminLoginResponseDTO;
import com.tareas.app.admin.dto.AdminRefreshResponseDTO;
import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminRefreshTokenService;
import com.tareas.app.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminJwtService adminJwtService;
    private final AdminRefreshTokenService adminRefreshTokenService;

    @Transactional
    public AdminLoginResponseDTO login(AdminLoginDTO loginDTO) {
        String email = normalizarEmail(loginDTO.getEmail());
        log.info("=== ADMIN LOGIN === Email: {}", email);

        AdminUser adminUser = adminUserRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (!adminUser.getEnabled()) {
            log.warn("Intento de login con admin deshabilitado: {}", email);
            throw new DisabledException("Admin deshabilitado");
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), adminUser.getPasswordHash())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        adminUser.setLastLogin(LocalDateTime.now());
        adminUserRepository.save(adminUser);

        String token = adminJwtService.generateToken(adminUser.getEmail());
        String refreshToken = adminRefreshTokenService.crearPara(adminUser);
        adminRefreshTokenService.limpiarExpirados();

        return new AdminLoginResponseDTO(
                token,
                refreshToken,
                adminUser.getId(),
                adminUser.getUsername(),
                adminUser.getEmail()
        );
    }

    public AdminRefreshResponseDTO refrescar(String tokenEnClaro) {
        return adminRefreshTokenService.rotar(tokenEnClaro);
    }

    public void cerrarSesion(String tokenEnClaro) {
        adminRefreshTokenService.revocar(tokenEnClaro);
    }

    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
