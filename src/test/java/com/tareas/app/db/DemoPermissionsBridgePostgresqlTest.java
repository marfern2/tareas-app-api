package com.tareas.app.db;

import com.tareas.app.admin.repository.AdminAuditEventRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.admin.security.AdminUserDetailsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@Testcontainers
class DemoPermissionsBridgePostgresqlTest {
    private static final String EMAIL = "demo-bridge@example.invalid";
    private static final String PASSWORD = "bridge-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void prepareFutureSchema(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // V8 expands the CHECK; rows are inserted only in this disposable test database.
        jdbc.update("INSERT INTO admin_users (username, email, password_hash) VALUES (?, ?, ?)",
                "demo-bridge", EMAIL, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode(PASSWORD));
        for (String permission : new String[]{"DEMO_READ", "DEMO_WRITE", "DEMO_PUBLISH", "DEMO_RESTORE"}) {
            jdbc.update("INSERT INTO admin_permissions (admin_user_id, permission) "
                    + "SELECT id, ? FROM admin_users WHERE email = ?", permission, EMAIL);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUserRepository admins;
    @Autowired AdminUserDetailsService detailsService;
    @Autowired AdminJwtService jwtService;
    @Autowired AdminAuditEventRepository audit;

    @Test
    void futurePermissionRowsLoadAuthenticateAndGrantNoRealDataAccess() throws Exception {
        // Spring has already started with Flyway enabled and Hibernate ddl-auto=validate.
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");

        var admin = admins.findByEmail(EMAIL).orElseThrow();
        assertThat(admin.getPermissions()).containsExactlyInAnyOrder(
                AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE,
                AdminPermission.DEMO_PUBLISH, AdminPermission.DEMO_RESTORE);
        Set<String> authorities = detailsService.loadUserByUsername(EMAIL).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(authorities).containsExactlyInAnyOrder(
                "DEMO_READ", "DEMO_WRITE", "DEMO_PUBLISH", "DEMO_RESTORE");

        String response = mvc.perform(post("/api/admin/auth/login")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).get("token").asText();
        assertThat(jwtService.extractUsername(token)).isEqualTo(EMAIL);

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/1").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"username\":\"blocked\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/1/tasks").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/demo").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(audit.findAll()).hasSize(3).allSatisfy(event -> {
            assertThat(event.getAdminUserId()).isEqualTo(admin.getId());
            assertThat(event.getOutcome()).isEqualTo("FAILURE");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tareas", Integer.class)).isZero();
    }

    // Snapshot of AdminPermission immediately before this bridge. JPA's STRING enum conversion
    // uses Enum.valueOf; every future row fails to load in that older image.
    private enum PreBridgePermission { ADMIN_READ, USER_WRITE, USER_DELETE, TASK_WRITE }

    @Test
    void previousEnumCannotReadFuturePermissionRows() {
        for (String permission : new String[]{"DEMO_READ", "DEMO_WRITE", "DEMO_PUBLISH", "DEMO_RESTORE"}) {
            assertThatThrownBy(() -> Enum.valueOf(PreBridgePermission.class, permission))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
