package com.tareas.app.auth;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.RefreshTokenRepository;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import com.tareas.app.repository.UsuarioRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DisabledUserIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TareaRepository tareaRepository;

    @Autowired
    private TipoTareaRepository tipoTareaRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminRefreshTokenRepository adminRefreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "Prueba123";
    private static final String ADMIN_PASSWORD = "Admin123";
    private static final AtomicInteger SUF = new AtomicInteger();

    @BeforeEach
    void setUp() {
        adminRefreshTokenRepository.deleteAll();
        adminUserRepository.deleteAll();
        tareaRepository.deleteAll();
        tipoTareaRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        usuarioRepository.deleteAll();
    }

    private String email() {
        return "dis" + SUF.incrementAndGet() + "@test.local";
    }

    private String adminEmail() {
        return "admin-dis" + SUF.incrementAndGet() + "@test.local";
    }

    private AdminUser crearAdmin(String email) {
        AdminUser admin = AdminUser.builder()
                .username("admin-dis" + SUF.get())
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

    private long registrar(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dis-user" + SUF.get() + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String loginToken(String email) throws Exception {
        return login(email).get("token").asText();
    }

    private String loginRefreshToken(String email) throws Exception {
        return login(email).get("refreshToken").asText();
    }

    private void deshabilitarUsuario(long usuarioId, String adminToken) throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/enabled", usuarioId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
    }

    private void habilitarUsuario(long usuarioId, String adminToken) throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/enabled", usuarioId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // 1. enabled=true login funciona
    // ========================================================================
    @Test
    @DisplayName("enabled=true: login funciona correctamente")
    void loginConEnabledTrueFunciona() throws Exception {
        String uEmail = email();
        registrar(uEmail);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uEmail + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    // ========================================================================
    // 2. enabled=false login falla
    // ========================================================================
    @Test
    @DisplayName("enabled=false: login devuelve 401")
    void loginConEnabledFalseFalla() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = email();
        long userId = registrar(uEmail);
        deshabilitarUsuario(userId, adminToken);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uEmail + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales inválidas"));
    }

    // ========================================================================
    // 3. access token emitido ANTES de disable funciona antes del cambio
    // ========================================================================
    @Test
    @DisplayName("Access token emitido antes de disable funciona antes del cambio")
    void accessTokenAntesDeDisableFunciona() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = email();
        long userId = registrar(uEmail);
        String token = loginToken(uEmail);

        // Token emitido mientras usuario estaba enabled -> funciona
        mockMvc.perform(get("/tareas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Ahora deshabilitar
        deshabilitarUsuario(userId, adminToken);

        // El mismo token ahora debe fallar
        mockMvc.perform(get("/tareas").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 4. después de enabled=false, el MISMO access token recibe 401
    // ========================================================================
    @Test
    @DisplayName("Después de enabled=false, access token existente recibe 401")
    void accessTokenExistenteRecibe401TrasDisable() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = email();
        long userId = registrar(uEmail);

        // Crear recurso para verificar que el token SÍ funciona antes
        String token = loginToken(uEmail);
        long tipoId = crearTipo(token, "tipo-antes");
        assertThat(tipoId).isPositive();

        // Deshabilitar
        deshabilitarUsuario(userId, adminToken);

        // El token ahora falla en endpoint protegido
        mockMvc.perform(get("/tareas").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // También falla al listar tareas
        mockMvc.perform(get("/tareas").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 5. refresh token revocado después de disable falla
    // ========================================================================
    @Test
    @DisplayName("Refresh token se revoca al deshabilitar y no puede usarse")
    void refreshTokenRevocadoTrasDisableFalla() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = email();
        long userId = registrar(uEmail);
        String refreshToken = loginRefreshToken(uEmail);

        // Deshabilitar -> revoca refresh tokens
        deshabilitarUsuario(userId, adminToken);

        // El refresh token ya no funciona
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 6. enabled=true de nuevo permite nuevo login
    // ========================================================================
    @Test
    @DisplayName("Re-habilitar usuario permite nuevo login")
    void reHabilitarPermitirNuevoLogin() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = email();
        long userId = registrar(uEmail);
        deshabilitarUsuario(userId, adminToken);

        // Login falla mientras está deshabilitado
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uEmail + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        // Re-habilitar
        habilitarUsuario(userId, adminToken);

        // Login vuelve a funcionar
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uEmail + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ========================================================================
    // 7. refresh token viejo sigue revocado tras reactivar
    // ========================================================================
    @Test
    @DisplayName("Refresh token revocado sigue revocado tras reactivar usuario")
    void refreshTokenRevocadoSigueRevocadoTrasReactivar() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = email();
        long userId = registrar(uEmail);
        String refreshTokenAntiguo = loginRefreshToken(uEmail);

        // Deshabilitar (revoca tokens)
        deshabilitarUsuario(userId, adminToken);

        // Re-habilitar
        habilitarUsuario(userId, adminToken);

        // El refresh token antiguo sigue revocado
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshTokenAntiguo + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 8. usuario enabled=true normal no tiene regresiones
    // ========================================================================
    @Test
    @DisplayName("Usuario enabled=true mantiene funcionalidad completa")
    void usuarioEnabledTrueSinRegresiones() throws Exception {
        String uEmail = email();
        registrar(uEmail);

        // Login
        JsonNode loginBody = login(uEmail);
        String token = loginBody.get("token").asText();
        String refreshToken = loginBody.get("refreshToken").asText();

        // Crear recursos
        long tipoId = crearTipo(token, "tipo-normal");
        long tareaId = crearTarea(token, tipoId, "tarea-normal");
        assertThat(tipoId).isPositive();
        assertThat(tareaId).isPositive();

        // Listar tareas
        mockMvc.perform(get("/tareas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Refresh
        String newRefresh = "new-refresh-" + SUF.incrementAndGet();
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // 9. admin auth sigue funcionando
    // ========================================================================
    @Test
    @DisplayName("Admin auth no se ve afectado por cambios en usuario normal")
    void adminAuthNoAfectado() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        // Admin puede listar usuarios
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // 10. JWT admin no se ve afectado por disable de usuario normal
    // ========================================================================
    @Test
    @DisplayName("JWT admin no se ve afectado por disable de usuario normal")
    void jwtAdminNoAfectadoPorDisable() throws Exception {
        String admEmail = adminEmail();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        // Deshabilitar usuario normal
        String uEmail = email();
        long userId = registrar(uEmail);
        deshabilitarUsuario(userId, adminToken);

        // Admin sigue funcionando
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Admin puede re-habilitar al usuario
        mockMvc.perform(patch("/api/admin/users/{id}/enabled", userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // Helpers
    // ========================================================================
    private long crearTipo(String token, String nombre) throws Exception {
        MvcResult result = mockMvc.perform(post("/tipos-tarea")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"" + nombre + "\",\"color\":\"#123456\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long crearTarea(String token, long tipoId, String titulo) throws Exception {
        MvcResult result = mockMvc.perform(post("/tareas")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"" + titulo + "\",\"fecha\":\"" + java.time.LocalDate.now().plusDays(1) + "\",\"tipoTareaId\":" + tipoId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
