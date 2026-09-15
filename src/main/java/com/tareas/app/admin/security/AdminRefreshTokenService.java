package com.tareas.app.admin.security;

import com.tareas.app.admin.exception.AdminRefreshTokenNoValidoException;
import com.tareas.app.admin.model.AdminRefreshToken;
import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.dto.AdminRefreshResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final AdminRefreshTokenRepository adminRefreshTokenRepository;
    private final AdminJwtService adminJwtService;

    @Value("${jwt.admin.refresh-expiration-days:7}")
    private long refreshExpirationDays;

    public String crearPara(AdminUser adminUser) {
        String tokenEnClaro = generarToken();
        LocalDateTime ahora = LocalDateTime.now();

        AdminRefreshToken entidad = AdminRefreshToken.builder()
                .adminUser(adminUser)
                .tokenHash(sha256Hex(tokenEnClaro))
                .createdAt(ahora)
                .expiresAt(ahora.plusDays(refreshExpirationDays))
                .build();

        adminRefreshTokenRepository.save(entidad);

        return tokenEnClaro;
    }

    @Transactional
    public AdminRefreshResponseDTO rotar(String tokenEnClaro) {
        if (tokenEnClaro == null || tokenEnClaro.isBlank()) {
            throw new AdminRefreshTokenNoValidoException();
        }

        String hash = sha256Hex(tokenEnClaro);
        AdminRefreshToken entidad = adminRefreshTokenRepository.findByTokenHashParaActualizar(hash)
                .orElseThrow(AdminRefreshTokenNoValidoException::new);

        LocalDateTime ahora = LocalDateTime.now();

        if (entidad.getRevokedAt() != null) {
            log.warn("Reintento de admin refresh token revocado (admin user id={})", entidad.getAdminUser().getId());
            throw new AdminRefreshTokenNoValidoException();
        }

        if (entidad.getExpiresAt().isBefore(ahora)) {
            log.warn("Reintento de admin refresh token expirado (admin user id={})", entidad.getAdminUser().getId());
            throw new AdminRefreshTokenNoValidoException();
        }

        entidad.setRevokedAt(ahora);
        adminRefreshTokenRepository.save(entidad);

        String nuevoRefresh = crearPara(entidad.getAdminUser());
        String nuevoAccess = adminJwtService.generateToken(entidad.getAdminUser().getEmail());

        limpiarExpirados();

        return new AdminRefreshResponseDTO(nuevoAccess, nuevoRefresh);
    }

    @Transactional
    public void revocar(String tokenEnClaro) {
        if (tokenEnClaro == null || tokenEnClaro.isBlank()) {
            return;
        }

        String hash = sha256Hex(tokenEnClaro);
        adminRefreshTokenRepository.findByTokenHash(hash).ifPresent(entidad -> {
            if (entidad.getRevokedAt() == null) {
                entidad.setRevokedAt(LocalDateTime.now());
                adminRefreshTokenRepository.save(entidad);
                log.debug("Admin refresh token revocado por logout");
            }
        });
    }

    @Transactional
    public void limpiarExpirados() {
        LocalDateTime ahora = LocalDateTime.now();
        int borrados = adminRefreshTokenRepository.eliminarExpiradosORevocados(ahora, ahora.minusDays(1));
        if (borrados > 0) {
            log.info("Limpiados {} admin refresh tokens expirados/revocados", borrados);
        }
    }

    private String generarToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String sha256Hex(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
