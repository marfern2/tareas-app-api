package com.tareas.app.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayV8UpgradeTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void devV7ToV8PreservesAdminsAndBridgeFlywayAcceptsDemoRows(@TempDir Path bridgeMigrations) throws IOException {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("7")).load().migrate();
        jdbc.update("INSERT INTO admin_users (username,email,password_hash) VALUES ('existing','existing@example.invalid','hash')");
        jdbc.update("INSERT INTO admin_permissions (admin_user_id,permission) "
                + "SELECT id,'ADMIN_READ' FROM admin_users WHERE username='existing'");

        Flyway current = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(jdbc.queryForList("SELECT permission FROM admin_permissions", String.class))
                .containsExactly("ADMIN_READ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_permissions WHERE permission LIKE 'DEMO_%'", Integer.class)).isZero();
        for (String table : List.of("demo_users", "demo_task_types", "demo_tasks", "demo_catalog_control")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }

        jdbc.update("INSERT INTO admin_permissions (admin_user_id,permission) "
                + "SELECT id,'DEMO_READ' FROM admin_users WHERE username='existing'");
        for (int version = 1; version <= 7; version++) {
            String file = switch (version) {
                case 1 -> "V1__baseline_schema.sql";
                case 2 -> "V2__refresh_tokens.sql";
                case 3 -> "V3__admin_auth.sql";
                case 4 -> "V4__add_enabled_to_usuarios.sql";
                case 5 -> "V5__admin_permissions_and_audit.sql";
                case 6 -> "V6__revoke_disabled_admin_sessions.sql";
                default -> "V7__dev_fixture_provenance.sql";
            };
            try (var source = getClass().getResourceAsStream("/db/migration/" + file)) {
                assertThat(source).isNotNull();
                Files.copy(source, bridgeMigrations.resolve(file));
            }
        }
        Flyway bridge = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + bridgeMigrations.toAbsolutePath()).load();
        bridge.validate();
        assertThat(bridge.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT permission FROM admin_permissions ORDER BY permission", String.class))
                .containsExactly("ADMIN_READ", "DEMO_READ");
    }
}
