package com.tareas.app.config;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("dev-admin-rotation & !prod")
public class DevAdminCredentialRotationService {

    private final AdminUserRepository adminUsers;
    private final AdminRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public DevAdminCredentialRotationService(
            AdminUserRepository adminUsers,
            AdminRefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate
    ) {
        this.adminUsers = adminUsers;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int rotate(String username, String targetEmail, CharSequence newPassword) {
        if (!"donit_dev".equals(jdbcTemplate.queryForObject("SELECT current_database()", String.class))) {
            throw new IllegalStateException("La rotacion DEV requiere la base donit_dev");
        }
        AdminUser admin = adminUsers.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("No existe el admin privado DEV esperado"));

        adminUsers.findByEmail(targetEmail)
                .filter(existing -> !existing.getId().equals(admin.getId()))
                .ifPresent(existing -> {
                    throw new IllegalStateException("El email objetivo ya pertenece a otra cuenta");
                });

        if (targetEmail.equals(admin.getEmail())
                && passwordEncoder.matches(newPassword, admin.getPasswordHash())) {
            return 0;
        }

        admin.setEmail(targetEmail);
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        adminUsers.save(admin);
        return refreshTokens.deleteAllByAdminUserId(admin.getId());
    }
}
