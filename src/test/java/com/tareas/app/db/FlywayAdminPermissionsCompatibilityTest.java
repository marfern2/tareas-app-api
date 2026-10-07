package com.tareas.app.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayAdminPermissionsCompatibilityTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir
    Path oldImageMigrations;

    @Test
    void existingAdminsKeepAccessAndNewAdminsStartWithoutPermissions() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("4")).load().migrate();
        jdbc.update("INSERT INTO admin_users (username, email, password_hash) VALUES (?, ?, ?)",
                "legacy", "legacy@example.invalid", "hash");
        jdbc.update("INSERT INTO admin_users (username, email, password_hash, enabled) VALUES (?, ?, ?, false)",
                "disabled", "disabled@example.invalid", "hash");
        jdbc.update("INSERT INTO admin_refresh_tokens (admin_user_id, token_hash, expires_at) "
                        + "SELECT id, 'disabled-before-v6', now() + interval '1 day' FROM admin_users "
                        + "WHERE username = 'disabled'");

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");

        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM admin_refresh_tokens "
                + "WHERE token_hash = 'disabled-before-v6'", Boolean.class)).isTrue();

        assertThat(jdbc.queryForList("SELECT permission FROM admin_permissions WHERE admin_user_id = "
                + "(SELECT id FROM admin_users WHERE username='legacy') ORDER BY permission", String.class))
                .containsExactly("ADMIN_READ", "TASK_WRITE", "USER_DELETE", "USER_WRITE");
        assertThat(jdbc.queryForList("SELECT DISTINCT permission FROM admin_permissions ORDER BY permission", String.class))
                .containsExactly("ADMIN_READ", "TASK_WRITE", "USER_DELETE", "USER_WRITE");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO admin_permissions (admin_user_id, permission) "
                + "SELECT id, 'DEMO_READ' FROM admin_users WHERE username='legacy'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO admin_users (username, email, password_hash) VALUES (?, ?, ?)",
                "new", "new@example.invalid", "hash");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_permissions WHERE admin_user_id = "
                + "(SELECT id FROM admin_users WHERE username='new')", Integer.class)).isZero();
        jdbc.update("INSERT INTO usuarios (username, email, password) VALUES (?, ?, ?)",
                "user", "user@example.invalid", "hash");
        assertThat(jdbc.queryForObject("SELECT protected_from_admin_mutation FROM usuarios LIMIT 1", Boolean.class))
                .isFalse();

        // Simulate rolling back the image: the previous classpath contains only V1-V4.
        for (String name : new String[]{"V1__baseline_schema.sql", "V2__refresh_tokens.sql",
                "V3__admin_auth.sql", "V4__add_enabled_to_usuarios.sql"}) {
            try (var resource = getClass().getResourceAsStream("/db/migration/" + name)) {
                assertThat(resource).isNotNull();
                Files.copy(resource, oldImageMigrations.resolve(name));
            }
        }
        Flyway previousImage = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + oldImageMigrations).load();
        previousImage.validate();
        assertThat(previousImage.migrate().migrationsExecuted).isZero();
    }
}
