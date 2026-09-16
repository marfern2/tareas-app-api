package com.tareas.app.security;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminRefreshTokenRepository adminRefreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String ADMIN_PASSWORD = "Admin123";
    private static final AtomicInteger SUF = new AtomicInteger();

    private static final String ORIGIN_ADMIN = "https://admin-donit.marfern.dev";
    private static final String ORIGIN_DEV = "http://localhost:4200";
    private static final String ORIGIN_EVIL = "https://evil.example";

    @BeforeEach
    void setUp() {
        adminRefreshTokenRepository.deleteAll();
        adminUserRepository.deleteAll();
    }

    private String email() {
        return "admin" + SUF.incrementAndGet() + "@test.local";
    }

    private AdminUser crearAdmin(String email) {
        AdminUser admin = AdminUser.builder()
                .username("admin-" + SUF.get())
                .email(email)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        return adminUserRepository.save(admin);
    }

    private String loginAdmin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    // ========================================================================
    // 1. PREFLIGHT admin desde origen permitido -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight OPTIONS admin desde origen permitido devuelve 200 con cabeceras CORS")
    void preflightAdminOrigenPermitido() throws Exception {
        mockMvc.perform(options("/api/admin/tareas")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().exists("Access-Control-Allow-Methods"))
                .andExpect(header().exists("Access-Control-Allow-Headers"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    // ========================================================================
    // 2. PREFLIGHT admin desde localhost:4200 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight OPTIONS admin desde localhost:4200 devuelve 200 con cabeceras CORS")
    void preflightAdminLocalhostPermitido() throws Exception {
        mockMvc.perform(options("/api/admin/tareas")
                        .header("Origin", ORIGIN_DEV)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_DEV));
    }

    // ========================================================================
    // 3. PREFLIGHT desde origen NO permitido -> sin CORS
    // ========================================================================
    @Test
    @DisplayName("Preflight OPTIONS desde origen evil no obtiene cabeceras CORS")
    void preflightOrigenNoPermitidoSinCors() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/admin/tareas")
                        .header("Origin", ORIGIN_EVIL)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isNull();
    }

    // ========================================================================
    // 4. OPTIONS admin no requiere JWT
    // ========================================================================
    @Test
    @DisplayName("OPTIONS /api/admin/tareas no requiere JWT y devuelve 200/204")
    void optionsAdminNoRequiereJwt() throws Exception {
        mockMvc.perform(options("/api/admin/tareas")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(200, 204);
                });
    }

    // ========================================================================
    // 5. GET admin con Origin permitido incluye cabecera CORS
    // ========================================================================
    @Test
    @DisplayName("GET admin con token válido y origen permitido incluye Access-Control-Allow-Origin")
    void getAdminConOrigenPermitidoIncluyeCors() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/lo-que-sea")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Origin", ORIGIN_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN));
    }

    // ========================================================================
    // 6. GET admin con Origin evil NO incluye CORS
    // ========================================================================
    @Test
    @DisplayName("GET admin con origen evil no incluye Access-Control-Allow-Origin")
    void getAdminConOrigenEvilNoIncluyeCors() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        MvcResult result = mockMvc.perform(get("/api/admin/lo-que-sea")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Origin", ORIGIN_EVIL))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isNull();
    }

    // ========================================================================
    // 7. Auth admin existente sigue funcionando (login)
    // ========================================================================
    @Test
    @DisplayName("Login admin sigue funcionando correctamente")
    void authAdminSigueFuncionando() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + admEmail + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ========================================================================
    // 8. JWT Android no puede acceder a admin
    // ========================================================================
    @Test
    @DisplayName("JWT Android no puede acceder a /api/admin/**")
    void jwtAndroidNoAccedeAdmin() throws Exception {
        String userEmail = "android-cors" + SUF.incrementAndGet() + "@test.local";
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"normal-user" + SUF.get() + "\",\"email\":\"" + userEmail + "\",\"password\":\"Pass123\"}"))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + userEmail + "\",\"password\":\"Pass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String normalToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/admin/tareas")
                        .header("Authorization", "Bearer " + normalToken)
                        .header("Origin", ORIGIN_ADMIN))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 9. Preflight PATCH /api/admin/users/1 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight PATCH /api/admin/users/1 desde origen permitido devuelve CORS correcto")
    void preflightPatchAdminUsers() throws Exception {
        mockMvc.perform(options("/api/admin/users/1")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")))
                .andExpect(header().exists("Access-Control-Allow-Headers"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    // ========================================================================
    // 10. Preflight DELETE /api/admin/users/1 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight DELETE /api/admin/users/1 desde origen permitido devuelve CORS correcto")
    void preflightDeleteAdminUsers() throws Exception {
        mockMvc.perform(options("/api/admin/users/1")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    // ========================================================================
    // 11. Preflight PATCH /api/admin/users/1/tasks/1 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight PATCH /api/admin/users/1/tasks/1 desde origen permitido devuelve CORS correcto")
    void preflightPatchAdminUsersTasks() throws Exception {
        mockMvc.perform(options("/api/admin/users/1/tasks/1")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    // ========================================================================
    // 12. Preflight DELETE /api/admin/users/1/tasks/1 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight DELETE /api/admin/users/1/tasks/1 desde origen permitido devuelve CORS correcto")
    void preflightDeleteAdminUsersTasks() throws Exception {
        mockMvc.perform(options("/api/admin/users/1/tasks/1")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    // ========================================================================
    // 13. Preflight PATCH /api/admin/users/1/task-types/1 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight PATCH /api/admin/users/1/task-types/1 desde origen permitido devuelve CORS correcto")
    void preflightPatchAdminUsersTaskTypes() throws Exception {
        mockMvc.perform(options("/api/admin/users/1/task-types/1")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    // ========================================================================
    // 14. Preflight DELETE /api/admin/users/1/task-types/1 -> permitido
    // ========================================================================
    @Test
    @DisplayName("Preflight DELETE /api/admin/users/1/task-types/1 desde origen permitido devuelve CORS correcto")
    void preflightDeleteAdminUsersTaskTypes() throws Exception {
        mockMvc.perform(options("/api/admin/users/1/task-types/1")
                        .header("Origin", ORIGIN_ADMIN)
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN_ADMIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    // ========================================================================
    // 15. Preflight PATCH desde origen NO permitido -> sin CORS
    // ========================================================================
    @Test
    @DisplayName("Preflight PATCH admin desde origen evil no obtiene cabeceras CORS")
    void preflightPatchOrigenNoPermitidoSinCors() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/admin/users/1")
                        .header("Origin", ORIGIN_EVIL)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isNull();
    }

    // ========================================================================
    // 16. Preflight DELETE desde origen NO permitido -> sin CORS
    // ========================================================================
    @Test
    @DisplayName("Preflight DELETE admin desde origen evil no obtiene cabeceras CORS")
    void preflightDeleteOrigenNoPermitidoSinCors() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/admin/users/1")
                        .header("Origin", ORIGIN_EVIL)
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isNull();
    }

    // ========================================================================
    // 17. JWT admin no puede acceder a endpoints normales
    // ========================================================================
    @Test
    @DisplayName("JWT admin no puede acceder a /tareas")
    void jwtAdminNoAccedeAndroid() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/tareas")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }
}
