package com.tareas.app.demo;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminAuditEventRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.demo.fixtures.FixtureManifest;
import com.tareas.app.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@Testcontainers
class DemoFixturePostgresqlTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final String PREVIEW = "/api/admin/demo/fixtures/restore-preview";
    private static final String RESTORE = "/api/admin/demo/fixtures/restore";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUserRepository admins;
    @Autowired AdminAuditEventRepository audits;
    @Autowired AdminJwtService jwt;
    @Autowired JwtService androidJwt;
    @Autowired FixtureManifest manifest;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM demo_fixture_registry");
        jdbc.update("DELETE FROM demo_tasks");
        jdbc.update("DELETE FROM demo_task_types");
        jdbc.update("DELETE FROM demo_users");
        jdbc.update("UPDATE demo_catalog_control SET revision=0, manifest_version=NULL, last_restored_at=NULL, last_restore_id=NULL");
        audits.deleteAll();
    }

    private String token(AdminPermission... permissions) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "fixture-" + suffix + "@example.invalid";
        admins.save(AdminUser.builder().username("fixture-" + suffix).email(email)
                .passwordHash("unused").enabled(true).createdAt(LocalDateTime.now())
                .permissions(permissions.length == 0 ? EnumSet.noneOf(AdminPermission.class)
                        : EnumSet.of(permissions[0], permissions)).build());
        return "Bearer " + jwt.generateToken(email);
    }

    private JsonNode json(String text) throws Exception { return mapper.readTree(text); }

    private String etag(String auth) throws Exception {
        return mvc.perform(get(PREVIEW).header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    private JsonNode restore(String auth, String etag) throws Exception {
        return json(mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", etag)
                .header("X-Request-ID", "fixture-restore-test")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void createsStableDraftCatalogAndPreservesCustomAndRealData() throws Exception {
        String auth = token(AdminPermission.DEMO_RESTORE);
        mvc.perform(get("/api/admin/demo/users").header("Authorization", auth))
                .andExpect(status().isForbidden());
        long realUsers = jdbc.queryForObject("SELECT count(*) FROM usuarios", Long.class);
        long realTasks = jdbc.queryForObject("SELECT count(*) FROM tareas", Long.class);
        jdbc.update("INSERT INTO demo_users (handle, display_name) VALUES ('custom-fixture-test', 'Custom')");
        long customId = jdbc.queryForObject("SELECT id FROM demo_users WHERE handle='custom-fixture-test'", Long.class);
        UUID customPublicId = jdbc.queryForObject("SELECT public_id FROM demo_users WHERE id=?", UUID.class, customId);

        String firstTag = etag(auth);
        JsonNode first = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(first.get("users").get("create").size()).isEqualTo(6);
        assertThat(first.get("types").get("create").size()).isEqualTo(12);
        assertThat(first.get("tasks").get("create").size()).isEqualTo(24);
        assertThat(first.get("customRecords").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_fixture_registry", Long.class)).isZero();

        restore(auth, firstTag);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE fixture_key IS NOT NULL", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_task_types WHERE fixture_key IS NOT NULL", Long.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_tasks WHERE fixture_key IS NOT NULL", Long.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE publication_status='PUBLISHED'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_task_types WHERE publication_status='PUBLISHED'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_tasks WHERE publication_status='PUBLISHED'", Long.class)).isZero();
        assertThat(json(mvc.perform(get("/api/public/demo/users")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("totalElements").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT public_id FROM demo_users WHERE id=?", UUID.class, customId)).isEqualTo(customPublicId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Long.class)).isEqualTo(realUsers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tareas", Long.class)).isEqualTo(realTasks);

        UUID fixtureId = manifest.document().users().getFirst().publicId();
        String key = manifest.document().users().getFirst().fixtureKey();
        jdbc.update("UPDATE demo_users SET display_name='Manual change' WHERE fixture_key=?", key);
        mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", etag(auth) + "stale"))
                .andExpect(status().isPreconditionFailed());
        JsonNode changed = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(changed.get("users").get("update").toString()).contains(key);
        restore(auth, etag(auth));
        assertThat(jdbc.queryForObject("SELECT display_name FROM demo_users WHERE fixture_key=?", String.class, key))
                .isEqualTo(manifest.document().users().getFirst().displayName());
        assertThat(jdbc.queryForObject("SELECT public_id FROM demo_users WHERE fixture_key=?", UUID.class, key))
                .isEqualTo(fixtureId);
        JsonNode repeated = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(repeated.get("users").get("update").size()).isZero();
        assertThat(repeated.get("tasks").get("update").size()).isZero();
        restore(auth, etag(auth));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE fixture_key IS NULL", Long.class)).isEqualTo(1);
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getOperation()).isEqualTo("DEMO_FIXTURE_RESTORE");
            assertThat(a.getOutcome()).isEqualTo("SUCCESS");
            assertThat(a.getPreviousRevision()).isNotNull();
            assertThat(a.getNewRevision()).isGreaterThan(a.getPreviousRevision());
            assertThat(a.getRestoreCounts()).contains("delete=0");
            assertThat(a.getCorrelationId()).isEqualTo("fixture-restore-test");
        });
    }

    @Test
    void permissionAndPreconditionAreIndependent() throws Exception {
        mvc.perform(get(PREVIEW)).andExpect(status().isUnauthorized());
        mvc.perform(post(RESTORE)).andExpect(status().isUnauthorized());
        for (AdminPermission permission : new AdminPermission[]{AdminPermission.ADMIN_READ,
                AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE, AdminPermission.DEMO_PUBLISH}) {
            String auth = token(permission);
            mvc.perform(get(PREVIEW).header("Authorization", auth)).andExpect(status().isForbidden());
            mvc.perform(post(RESTORE).header("Authorization", auth)).andExpect(status().isForbidden());
        }
        String auth = token(AdminPermission.DEMO_RESTORE);
        mvc.perform(get(PREVIEW).header("Authorization", "Bearer " + androidJwt.generateToken("android")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(RESTORE).header("Authorization", auth)).andExpect(status().isPreconditionRequired());
        mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", "\"v99\""))
                .andExpect(status().isPreconditionFailed());
        restore(auth, etag(auth));
        assertThat(audits.findAll()).filteredOn(a -> "DEMO_FIXTURE_RESTORE".equals(a.getOperation()))
                .allSatisfy(a -> assertThat(a.getCorrelationId()).isNotBlank());
    }

    @Test
    void retiredFixturesRemainAsDraftAndConcurrentRestoresSerialize() throws Exception {
        String auth = token(AdminPermission.DEMO_RESTORE);
        restore(auth, etag(auth));
        long owner = jdbc.queryForObject("SELECT id FROM demo_users WHERE fixture_key=?", Long.class,
                manifest.document().users().getFirst().fixtureKey());
        long type = jdbc.queryForObject("SELECT id FROM demo_task_types WHERE fixture_key=?", Long.class,
                manifest.document().types().getFirst().fixtureKey());
        UUID oldId = UUID.randomUUID();
        String oldKey = "catalog:task:retired";
        long taskId = jdbc.queryForObject("""
                INSERT INTO demo_tasks (fixture_key, public_id, demo_user_id, demo_task_type_id, title, due_date,
                    publication_status, published_at)
                VALUES (?, ?, ?, ?, 'Retired synthetic task', CURRENT_DATE, 'PUBLISHED', now()) RETURNING id
                """, Long.class, oldKey, oldId, owner, type);
        jdbc.update("INSERT INTO demo_fixture_registry (kind, fixture_key, public_id, row_id) VALUES ('TASK', ?, ?, ?)",
                oldKey, oldId, taskId);
        JsonNode p = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(p.get("tasks").get("retired").toString()).contains(oldKey);
        restore(auth, etag(auth));
        assertThat(jdbc.queryForObject("SELECT publication_status FROM demo_tasks WHERE id=?", String.class, taskId))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT public_id FROM demo_tasks WHERE id=?", UUID.class, taskId)).isEqualTo(oldId);

        String observed = etag(auth);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return mvc.perform(post(RESTORE)
                    .header("Authorization", auth).header("If-Match", observed)).andReturn().getResponse().getStatus(); });
            var two = pool.submit(() -> { start.await(); return mvc.perform(post(RESTORE)
                    .header("Authorization", auth).header("If-Match", observed)).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(new int[]{one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(200, 412);
        }
    }

    @Test
    void auditFailureRollsBackWholeRestore() throws Exception {
        String auth = token(AdminPermission.DEMO_RESTORE);
        String observed = etag(auth);
        jdbc.execute("ALTER TABLE admin_audit_events ADD CONSTRAINT ck_test_restore_audit "
                + "CHECK (operation <> 'DEMO_FIXTURE_RESTORE')");
        try {
            mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", observed))
                    .andExpect(status().is5xxServerError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_fixture_registry", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT revision FROM demo_catalog_control WHERE id=1", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE admin_audit_events DROP CONSTRAINT ck_test_restore_audit");
        }
    }

    @Test
    void accidentalMatchingKeyIsConflictAndNeverAdopted() throws Exception {
        String key = manifest.document().users().getFirst().fixtureKey();
        UUID accidental = UUID.randomUUID();
        jdbc.update("INSERT INTO demo_users (fixture_key, public_id, handle, display_name) "
                        + "VALUES (?, ?, 'unregistered-demo', 'Custom row')", key, accidental);
        String auth = token(AdminPermission.DEMO_RESTORE);
        JsonNode p = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(p.get("users").get("conflicts").toString()).contains(key);
        mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", etag(auth)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT public_id FROM demo_users WHERE fixture_key=?", UUID.class, key))
                .isEqualTo(accidental);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_fixture_registry", Long.class)).isZero();
    }

    @Test
    void customChildrenSurviveAndPublishedChildBlocksDraftingParent() throws Exception {
        String auth = token(AdminPermission.DEMO_RESTORE);
        restore(auth, etag(auth));
        long owner = jdbc.queryForObject("SELECT id FROM demo_users WHERE fixture_key=?", Long.class,
                manifest.document().users().getFirst().fixtureKey());
        long type = jdbc.queryForObject("SELECT id FROM demo_task_types WHERE fixture_key=?", Long.class,
                manifest.document().types().getFirst().fixtureKey());
        long customType = jdbc.queryForObject("""
                INSERT INTO demo_task_types (demo_user_id, name, color)
                VALUES (?, 'Personal custom type', '#123ABC') RETURNING id
                """, Long.class, owner);
        long customTask = jdbc.queryForObject("""
                INSERT INTO demo_tasks (demo_user_id, demo_task_type_id, title, due_date)
                VALUES (?, ?, 'Personal custom task', CURRENT_DATE) RETURNING id
                """, Long.class, owner, type);
        restore(auth, etag(auth));
        assertThat(jdbc.queryForObject("SELECT name FROM demo_task_types WHERE id=?", String.class, customType))
                .isEqualTo("Personal custom type");
        assertThat(jdbc.queryForObject("SELECT title FROM demo_tasks WHERE id=?", String.class, customTask))
                .isEqualTo("Personal custom task");
        jdbc.update("UPDATE demo_users SET publication_status='PUBLISHED', published_at=now() WHERE id=?", owner);
        jdbc.update("UPDATE demo_task_types SET publication_status='PUBLISHED', published_at=now() WHERE id=?", customType);
        JsonNode preview = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.get("users").get("conflicts").toString())
                .contains(manifest.document().users().getFirst().fixtureKey());
        mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", etag(auth)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT publication_status FROM demo_users WHERE id=?", String.class, owner))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT publication_status FROM demo_task_types WHERE id=?", String.class, customType))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void olderManifestCannotReplaceNewerAppliedVersion() throws Exception {
        String auth = token(AdminPermission.DEMO_RESTORE);
        jdbc.update("UPDATE demo_catalog_control SET manifest_version=2 WHERE id=1");
        JsonNode p = json(mvc.perform(get(PREVIEW).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString());
        assertThat(p.get("catalogConflicts").toString()).contains("manifest_version_older_than_applied");
        mvc.perform(post(RESTORE).header("Authorization", auth).header("If-Match", etag(auth)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_catalog_control WHERE id=1", Long.class)).isZero();
    }
}
