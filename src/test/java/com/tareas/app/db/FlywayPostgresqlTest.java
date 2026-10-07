package com.tareas.app.db;

import com.tareas.app.admin.dto.AdminUpdateUserRequest;
import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.exception.AdminRefreshTokenNoValidoException;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminRefreshTokenService;
import com.tareas.app.admin.service.AdminAuthService;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.admin.security.AdminUserDetails;
import com.tareas.app.admin.service.AdminUserService;
import com.tareas.app.exception.ResourceConflictException;
import com.tareas.app.exception.ResourceNotFoundException;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import com.tareas.app.repository.UsuarioRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario objetivo: PostgreSQL VACIO
 *   -> Flyway aplica V1__baseline_schema.sql
 *   -> Hibernate validate (spring.jpa.hibernate.ddl-auto=validate)
 *   -> el contexto de Spring arranca y los repositorios funcionan.
 *
 * Usa Testcontainers con postgres:17-alpine (mismo tag que produccion).
 * No depende de H2 para validar el DDL de V1.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class FlywayPostgresqlTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private TipoTareaRepository tipoTareaRepository;

    @Autowired
    private TareaRepository tareaRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminRefreshTokenService adminRefreshTokenService;

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void disablingAdminRevokesAllSessionsWithoutChangingPermissionsOrAudit() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        AdminUser admin = adminUserRepository.save(AdminUser.builder().username("disable-" + marker)
                .email("disable-" + marker + "@example.invalid").passwordHash("hash")
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.ADMIN_READ)).build());
        String first = adminRefreshTokenService.crearPara(admin);
        String second = adminRefreshTokenService.crearPara(admin);
        int auditBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM admin_audit_events", Integer.class);

        jdbcTemplate.update("UPDATE admin_users SET enabled = false WHERE id = ?", admin.getId());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_refresh_tokens "
                + "WHERE admin_user_id = ? AND revoked_at IS NOT NULL", Integer.class, admin.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_permissions "
                + "WHERE admin_user_id = ?", Integer.class, admin.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_audit_events", Integer.class))
                .isEqualTo(auditBefore);
        assertThatThrownBy(() -> adminAuthService.refrescar(first))
                .isInstanceOf(AdminRefreshTokenNoValidoException.class);
        jdbcTemplate.update("UPDATE admin_users SET enabled = true WHERE id = ?", admin.getId());
        assertThatThrownBy(() -> adminAuthService.refrescar(second))
                .isInstanceOf(AdminRefreshTokenNoValidoException.class);
    }

    @Test
    void concurrentDisableWinsBeforeRefresh() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        AdminUser admin = adminUserRepository.save(AdminUser.builder().username("race-" + marker)
                .email("race-" + marker + "@example.invalid").passwordHash("hash")
                .enabled(true).createdAt(LocalDateTime.now()).build());
        String raw = adminRefreshTokenService.crearPara(admin);
        CountDownLatch disabledButUncommitted = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var disable = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE admin_users SET enabled = false WHERE id = ?", admin.getId());
                disabledButUncommitted.countDown();
                try {
                    if (!releaseDisable.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertThat(disabledButUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
            var refresh = executor.submit(() -> {
                refreshStarted.countDown();
                return adminAuthService.refrescar(raw);
            });
            assertThat(refreshStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseDisable.countDown();
            disable.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> refresh.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AdminRefreshTokenNoValidoException.class);
        } finally {
            releaseDisable.countDown();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_refresh_tokens "
                + "WHERE admin_user_id = ? AND revoked_at IS NULL", Integer.class, admin.getId())).isZero();
    }

    @Test
    void concurrentRefreshFinishesFirstAndDisableRevokesRotatedSession() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        AdminUser admin = adminUserRepository.save(AdminUser.builder().username("rotate-" + marker)
                .email("rotate-" + marker + "@example.invalid").passwordHash("hash")
                .enabled(true).createdAt(LocalDateTime.now()).build());
        String raw = adminRefreshTokenService.crearPara(admin);
        CountDownLatch rotatedButUncommitted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refresh = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                String next = adminAuthService.refrescar(raw).getRefreshToken();
                rotatedButUncommitted.countDown();
                try {
                    if (!releaseRefresh.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return next;
            }));
            assertThat(rotatedButUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
            var disable = executor.submit(() -> jdbcTemplate.update(
                    "UPDATE admin_users SET enabled = false WHERE id = ?", admin.getId()));
            releaseRefresh.countDown();
            String next = refresh.get(10, TimeUnit.SECONDS);
            assertThat(disable.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> adminAuthService.refrescar(next))
                    .isInstanceOf(AdminRefreshTokenNoValidoException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_refresh_tokens "
                    + "WHERE admin_user_id = ? AND revoked_at IS NULL", Integer.class, admin.getId())).isZero();
        } finally {
            releaseRefresh.countDown();
        }
    }

    @Test
    void concurrentProtectionWinsBeforeAdminMutation() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        Usuario user = usuarioRepository.save(Usuario.builder().username("protected-" + marker)
                .email("protected-" + marker + "@example.invalid").password("hash").build());
        AdminUser admin = adminUserRepository.save(AdminUser.builder().username("concurrent-" + marker)
                .email("concurrent-" + marker + "@example.invalid").passwordHash("hash")
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.USER_WRITE)).build());
        AdminUserDetails details = new AdminUserDetails(admin);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var protector = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE id = ? FOR UPDATE", Long.class, user.getId());
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for mutation");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                jdbcTemplate.update("UPDATE usuarios SET protected_from_admin_mutation = true WHERE id = ?", user.getId());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var mutation = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
                try {
                    AdminUpdateUserRequest request = new AdminUpdateUserRequest();
                    request.setUsername("changed-" + marker);
                    mutationStarted.countDown();
                    try {
                        adminUserService.actualizarUsuario(user.getId(), request);
                        return false;
                    } catch (ResourceConflictException expected) {
                        return true;
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            release.countDown();
            protector.get(10, TimeUnit.SECONDS);
            assertThat(mutation.get(10, TimeUnit.SECONDS)).isTrue();
        }
        Usuario current = usuarioRepository.findById(user.getId()).orElseThrow();
        assertThat(current.getUsername()).isEqualTo(user.getUsername());
        assertThat(current.getProtectedFromAdminMutation()).isTrue();
    }

    @Test
    void concurrentAdminDeletesCommitOnlyOnce() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        Usuario user = usuarioRepository.save(Usuario.builder().username("delete-" + marker)
                .email("delete-" + marker + "@example.invalid").password("hash").build());
        AdminUser admin = adminUserRepository.save(AdminUser.builder().username("deleter-" + marker)
                .email("deleter-" + marker + "@example.invalid").passwordHash("hash")
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.USER_DELETE)).build());
        AdminUserDetails details = new AdminUserDetails(admin);
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<String> delete = () -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
            try {
                start.await(10, TimeUnit.SECONDS);
                try {
                    adminUserService.eliminarUsuario(user.getId());
                    return "deleted";
                } catch (ResourceNotFoundException expected) {
                    return "missing";
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(delete);
            var second = executor.submit(delete);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("deleted", "missing");
        }
        assertThat(usuarioRepository.findById(user.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_audit_events "
                + "WHERE operation = 'USER_DELETE' AND resource_id = ? AND outcome = 'SUCCESS'",
                Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Flyway ejecuta V1 a V7 en PostgreSQL vacio, validate pasa y los repositorios funcionan")
    void migracionDesdeCeroYFuncionamientoBasico() {
        // 1. flyway_schema_history contiene exactamente V1 a V7 (success=true)
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).hasSize(7);
        assertThat(history.get(0).get("version")).isEqualTo("1");
        assertThat(history.get(0).get("type")).isEqualTo("SQL");
        assertThat(history.get(0).get("success")).isEqualTo(true);
        assertThat(history.get(1).get("version")).isEqualTo("2");
        assertThat(history.get(1).get("type")).isEqualTo("SQL");
        assertThat(history.get(1).get("success")).isEqualTo(true);
        assertThat(history.get(2).get("version")).isEqualTo("3");
        assertThat(history.get(2).get("type")).isEqualTo("SQL");
        assertThat(history.get(2).get("success")).isEqualTo(true);
        assertThat(history.get(3).get("version")).isEqualTo("4");
        assertThat(history.get(3).get("type")).isEqualTo("SQL");
        assertThat(history.get(3).get("success")).isEqualTo(true);
        assertThat(history.get(4).get("version")).isEqualTo("5");
        assertThat(history.get(4).get("success")).isEqualTo(true);
        assertThat(history.get(5).get("version")).isEqualTo("6");
        assertThat(history.get(5).get("success")).isEqualTo(true);
        assertThat(history.get(6).get("version")).isEqualTo("7");
        assertThat(history.get(6).get("success")).isEqualTo(true);

        // 2. Flyway informa V1 a V7 como aplicadas y sin pendientes
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "1".equals(m.getVersion().getVersion()));
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "2".equals(m.getVersion().getVersion()));
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "3".equals(m.getVersion().getVersion()));
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "4".equals(m.getVersion().getVersion()));
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "5".equals(m.getVersion().getVersion()));
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "6".equals(m.getVersion().getVersion()));
        assertThat(applied).anyMatch(m -> m.getVersion() != null && "7".equals(m.getVersion().getVersion()));
        assertThat(flyway.info().pending()).isEmpty();

        // 3. Las tablas existen (Hibernate validate ya ha arrancado el contexto)
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name IN ('usuarios','tipos_tarea','tareas','refresh_tokens','admin_users','admin_refresh_tokens','admin_permissions','admin_audit_events') "
                        + "ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly(
                "admin_audit_events", "admin_permissions", "admin_refresh_tokens", "admin_users",
                "refresh_tokens", "tareas", "tipos_tarea", "usuarios");

        // 4. Repositorios funcionan y la identity genera IDs
        Usuario alice = Usuario.builder()
                .username("alice").email("alice@example.com").password("hash1").build();
        Usuario savedAlice = usuarioRepository.save(alice);
        assertThat(savedAlice.getId()).isNotNull().isPositive();

        Usuario bob = Usuario.builder()
                .username("bob").email("bob@example.com").password("hash2").build();
        Usuario savedBob = usuarioRepository.save(bob);
        assertThat(savedBob.getId()).isEqualTo(savedAlice.getId() + 1);

        TipoTarea tipo = TipoTarea.builder()
                .nombre("Casa").descripcion("Tareas del hogar").color("#4F46E5").usuario(savedAlice).build();
        TipoTarea savedTipo = tipoTareaRepository.save(tipo);
        assertThat(savedTipo.getId()).isNotNull().isPositive();

        Tarea tarea = Tarea.builder()
                .titulo("Lavar la ropa")
                .descripcion("Ropa blanca y oscura")
                .fecha(LocalDate.now().plusDays(1))
                .completada(false)
                .urgencia(1)
                .usuario(savedAlice)
                .tipoTarea(savedTipo)
                .build();
        Tarea savedTarea = tareaRepository.save(tarea);
        assertThat(savedTarea.getId()).isNotNull().isPositive();
        assertThat(savedTarea.getTitulo()).isEqualTo("Lavar la ropa");

        assertThat(tareaRepository.findByUsuarioEmailOrderByFechaAsc("alice@example.com")).hasSize(1);
        assertThat(tipoTareaRepository.findByUsuarioEmailOrderByNombreAsc("alice@example.com")).hasSize(1);

        // 5. UNIQUE username se aplica a nivel de base de datos
        Usuario dupUsername = Usuario.builder()
                .username("alice").email("otra@example.com").password("hash3").build();
        assertThatThrownBy(() -> usuarioRepository.save(dupUsername))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 6. UNIQUE email se aplica a nivel de base de datos
        Usuario dupEmail = Usuario.builder()
                .username("otro").email("alice@example.com").password("hash4").build();
        assertThatThrownBy(() -> usuarioRepository.save(dupEmail))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 7. FK realmente funciona: tipo_tarea inexistente -> violacion de FK
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tareas (titulo, fecha, urgencia, tipo_tarea_id, usuario_id) "
                        + "VALUES ('fk', CURRENT_DATE, 0, 999999, ?)",
                savedAlice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 8. CHECK urgencia (0..2) realmente funciona: 99 fuera de rango -> violacion de CHECK
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tareas (titulo, fecha, urgencia, tipo_tarea_id, usuario_id) "
                        + "VALUES ('check', CURRENT_DATE, 99, ?, ?)",
                savedTipo.getId(), savedAlice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 9. refresh_tokens: UNIQUE token_hash realmente funciona a nivel de BD
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (usuario_id, token_hash, expires_at) "
                        + "VALUES (?, ?, now() + interval '1 day')",
                savedAlice.getId(), "hash-muy-unico");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refresh_tokens (usuario_id, token_hash, expires_at) "
                        + "VALUES (?, ?, now() + interval '1 day')",
                savedAlice.getId(), "hash-muy-unico"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 10. FK refresh_tokens -> usuarios: usuario inexistente -> violacion de FK
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refresh_tokens (usuario_id, token_hash, expires_at) "
                        + "VALUES (999999, 'hash-sin-usuario', now() + interval '1 day')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 11. V3: admin_users: UNIQUE email se aplica
        jdbcTemplate.update(
                "INSERT INTO admin_users (username, email, password_hash, created_at) "
                        + "VALUES ('admin1', 'admin@test.com', 'hash1', now())");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO admin_users (username, email, password_hash, created_at) "
                        + "VALUES ('admin2', 'admin@test.com', 'hash2', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 12. V3: admin_users: UNIQUE username se aplica
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO admin_users (username, email, password_hash, created_at) "
                        + "VALUES ('admin1', 'other@test.com', 'hash3', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 13. V3: admin_refresh_tokens: UNIQUE token_hash se aplica
        jdbcTemplate.update(
                "INSERT INTO admin_refresh_tokens (admin_user_id, token_hash, expires_at) "
                        + "VALUES (1, 'admin-hash-unico', now() + interval '1 day')");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO admin_refresh_tokens (admin_user_id, token_hash, expires_at) "
                        + "VALUES (1, 'admin-hash-unico', now() + interval '1 day')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 14. V3: admin_refresh_tokens: FK admin_user inexistente -> violacion de FK
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO admin_refresh_tokens (admin_user_id, token_hash, expires_at) "
                        + "VALUES (999999, 'hash-sin-admin', now() + interval '1 day')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 15. V3: admin_users: enabled default es true
        Integer enabledCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_users WHERE username = 'admin1' AND enabled = true", Integer.class);
        assertThat(enabledCount).isEqualTo(1);
    }
}
