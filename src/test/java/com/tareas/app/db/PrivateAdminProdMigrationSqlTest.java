package com.tareas.app.db;

import com.tareas.app.admin.service.AdminTaskService;
import com.tareas.app.admin.service.AdminTaskTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class PrivateAdminProdMigrationSqlTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminTaskService adminTasks;

    @Autowired
    private AdminTaskTypeService adminTypes;

    @Test
    void listadosAdminAceptanBusquedaNulaEnPostgresql() {
        assertThat(adminTasks.listarTareas(null, null, null, null, null, 0, 20, null)
                .getTotalElements()).isZero();
        assertThat(adminTypes.listarTipos(null, 0, 20, null).getTotalElements()).isZero();
    }

    @Test
    void migracionPreservaHashRevocaSesionesYReejecucionFallaSinCambios() throws IOException {
        jdbc.update("INSERT INTO admin_users (id, username, email, password_hash) "
                + "VALUES (1, 'mar', 'old@example.invalid', 'hash-before')");
        jdbc.update("INSERT INTO admin_refresh_tokens (admin_user_id, token_hash, expires_at) "
                + "VALUES (1, 'session-before', now() + interval '1 day')");

        String script = Files.readString(Path.of("scripts/migrate-private-admin-prod.sh"));
        int heredoc = script.indexOf("<<'SQL'");
        int start = script.indexOf("BEGIN;\n", heredoc);
        int end = script.indexOf("\nCOMMIT;", start);
        assertThat(heredoc).isNotNegative();
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        String migrationSql = script.substring(start + "BEGIN;\n".length(), end);

        jdbc.execute(migrationSql);

        assertThat(jdbc.queryForObject("SELECT email FROM admin_users WHERE id = 1", String.class))
                .isEqualTo("admin@gmail.com");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM admin_users WHERE id = 1", String.class))
                .isEqualTo("hash-before");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_refresh_tokens", Integer.class))
                .isZero();

        assertThatThrownBy(() -> jdbc.execute(migrationSql))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("El email objetivo ya existe");
        assertThat(jdbc.queryForObject("SELECT email FROM admin_users WHERE id = 1", String.class))
                .isEqualTo("admin@gmail.com");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM admin_users WHERE id = 1", String.class))
                .isEqualTo("hash-before");
    }
}
