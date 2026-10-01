package com.tareas.app.config;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class DevAdminCredentialRotationServiceTest {

    @Test
    void actualizaEmailConBCryptRealYRevocaSesiones() {
        AdminUserRepository admins = mock(AdminUserRepository.class);
        AdminRefreshTokenRepository tokens = mock(AdminRefreshTokenRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JdbcTemplate jdbc = devDatabase();
        AdminUser admin = AdminUser.builder()
                .id(7L)
                .username("admin-dev")
                .email("old@example.invalid")
                .passwordHash("old-hash")
                .enabled(true)
                .build();
        when(admins.findByUsername("admin-dev")).thenReturn(Optional.of(admin));
        when(admins.findByEmail("admin@gmail.com")).thenReturn(Optional.empty());
        when(tokens.deleteAllByAdminUserId(7L)).thenReturn(3);

        int revoked = new DevAdminCredentialRotationService(admins, tokens, encoder, jdbc)
                .rotate("admin-dev", "admin@gmail.com", "new-private-value");

        assertThat(revoked).isEqualTo(3);
        assertThat(admin.getEmail()).isEqualTo("admin@gmail.com");
        assertThat(admin.getPasswordHash()).startsWith("$2a$");
        assertThat(encoder.matches("new-private-value", admin.getPasswordHash())).isTrue();
        verify(admins).save(admin);
        verify(tokens).deleteAllByAdminUserId(7L);
    }

    @Test
    void mismaCredencialYaAplicadaNoReescribeHashNiRevocaSesionesNuevas() {
        AdminUserRepository admins = mock(AdminUserRepository.class);
        AdminRefreshTokenRepository tokens = mock(AdminRefreshTokenRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JdbcTemplate jdbc = devDatabase();
        String existingHash = encoder.encode("already-applied-value");
        AdminUser admin = AdminUser.builder().id(7L).username("admin-dev")
                .email("admin@gmail.com").passwordHash(existingHash).enabled(true).build();
        when(admins.findByUsername("admin-dev")).thenReturn(Optional.of(admin));
        when(admins.findByEmail("admin@gmail.com")).thenReturn(Optional.of(admin));

        int revoked = new DevAdminCredentialRotationService(admins, tokens, encoder, jdbc)
                .rotate("admin-dev", "admin@gmail.com", "already-applied-value");

        assertThat(revoked).isZero();
        assertThat(admin.getPasswordHash()).isEqualTo(existingHash);
        verify(admins, never()).save(admin);
        verify(tokens, never()).deleteAllByAdminUserId(7L);
    }

    @Test
    void conflictoDeEmailNoModificaCredencialesNiSesiones() {
        AdminUserRepository admins = mock(AdminUserRepository.class);
        AdminRefreshTokenRepository tokens = mock(AdminRefreshTokenRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JdbcTemplate jdbc = devDatabase();
        AdminUser admin = AdminUser.builder().id(7L).username("admin-dev").build();
        AdminUser conflict = AdminUser.builder().id(8L).email("admin@gmail.com").build();
        when(admins.findByUsername("admin-dev")).thenReturn(Optional.of(admin));
        when(admins.findByEmail("admin@gmail.com")).thenReturn(Optional.of(conflict));

        DevAdminCredentialRotationService service =
                new DevAdminCredentialRotationService(admins, tokens, encoder, jdbc);

        assertThatThrownBy(() -> service.rotate("admin-dev", "admin@gmail.com", "unused-value"))
                .isInstanceOf(IllegalStateException.class);
        verify(encoder, never()).encode("unused-value");
        verify(tokens, never()).deleteAllByAdminUserId(7L);
    }

    @Test
    void rechazaBaseDeDatosDistintaAntesDeConsultarAdmins() {
        AdminUserRepository admins = mock(AdminUserRepository.class);
        AdminRefreshTokenRepository tokens = mock(AdminRefreshTokenRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("tareas_db");

        assertThatThrownBy(() -> new DevAdminCredentialRotationService(admins, tokens, encoder, jdbc)
                .rotate("admin-dev", "admin@gmail.com", "private-value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("donit_dev");
        verifyNoInteractions(admins, tokens, encoder);
    }

    private static JdbcTemplate devDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("donit_dev");
        return jdbc;
    }
}
