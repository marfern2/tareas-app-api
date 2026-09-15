package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminRefreshToken;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthIntegrationTest {

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

    @Autowired
    private JwtService normalJwtService;

    @Value("${jwt.admin.secret}")
    private String adminJwtSecret;

    @Value("${jwt.admin.issuer}")
    private String adminJwtIssuer;

    @Value("${jwt.admin.audience}")
    private String adminJwtAudience;

    private static final String ADMIN_PASSWORD = "Admin123";
    private static final AtomicInteger SUF = new AtomicInteger();

    @BeforeEach
    void setUp() {
        adminRefreshTokenRepository.deleteAll();
        adminUserRepository.deleteAll();
    }

    private SecretKey adminTestKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(adminJwtSecret));
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

    private AdminUser crearAdminDeshabilitado(String email) {
        AdminUser admin = AdminUser.builder()
                .username("disabled-" + SUF.get())
                .email(email)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .build();
        return adminUserRepository.save(admin);
    }

    private JsonNode login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String sha256Hex(String valor) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(valor.getBytes(StandardCharsets.UTF_8)));
    }

    // ========================================================================
    // 1. Login correcto devuelve access + refresh
    // ========================================================================
    @Test
    @DisplayName("Admin login correcto devuelve access token, refresh token y campos del admin")
    void loginCorrectoDevuelveTokens() throws Exception {
        String email = email();
        AdminUser admin = crearAdmin(email);

        JsonNode body = login(email);

        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("type").asText()).isEqualTo("Bearer");
        assertThat(body.get("id").asLong()).isEqualTo(admin.getId());
        assertThat(body.get("username").asText()).isEqualTo(admin.getUsername());
        assertThat(body.get("email").asText()).isEqualTo(email);

        // Verificar que last_login se actualizó
        AdminUser updated = adminUserRepository.findByEmail(email).orElseThrow();
        assertThat(updated.getLastLogin()).isNotNull();
    }

    // ========================================================================
    // 2. Login incorrecto -> 401
    // ========================================================================
    @Test
    @DisplayName("Admin login con credenciales incorrectas devuelve 401")
    void loginIncorrectoFalla() throws Exception {
        String email = email();
        crearAdmin(email);

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales inválidas"));
    }

    // ========================================================================
    // 3. Admin disabled -> 401
    // ========================================================================
    @Test
    @DisplayName("Admin deshabilitado no puede hacer login")
    void adminDeshabilitadoFalla() throws Exception {
        String email = email();
        crearAdminDeshabilitado(email);

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales inválidas"));
    }

    // ========================================================================
    // 4. Refresh válido rota tokens
    // ========================================================================
    @Test
    @DisplayName("Refresh válido produce nuevo access token y nuevo refresh token")
    void refreshValidoRotaTokens() throws Exception {
        String email = email();
        crearAdmin(email);
        JsonNode loginBody = login(email);

        String refreshTokenAnterior = loginBody.get("refreshToken").asText();
        JsonNode refreshBody = refresh(refreshTokenAnterior);

        assertThat(refreshBody.get("token").asText()).isNotBlank();
        assertThat(refreshBody.get("refreshToken").asText()).isNotBlank();
        assertThat(refreshBody.get("type").asText()).isEqualTo("Bearer");
        assertThat(refreshBody.get("refreshToken").asText())
                .isNotEqualTo(refreshTokenAnterior);

        // El nuevo access token se puede decodificar (no importa el endpoint, solo que el token es válido)
        String[] parts = refreshBody.get("token").asText().split("\\.");
        assertThat(parts).hasSize(3);
    }

    // ========================================================================
    // 5. Reutilizar refresh antiguo -> 401
    // ========================================================================
    @Test
    @DisplayName("Reutilizar refresh token antiguo después de rotación falla")
    void reutilizarRefreshAntiguoFalla() throws Exception {
        String email = email();
        crearAdmin(email);
        JsonNode loginBody = login(email);
        String refreshTokenAnterior = loginBody.get("refreshToken").asText();

        // Rotar
        refresh(refreshTokenAnterior);

        // Reintentar con el token anterior
        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshTokenAnterior + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 6. Refresh expirado -> 401
    // ========================================================================
    @Test
    @DisplayName("Refresh token expirado falla")
    void refreshExpiradoFalla() throws Exception {
        String email = email();
        AdminUser admin = crearAdmin(email);
        JsonNode loginBody = login(email);
        String raw = loginBody.get("refreshToken").asText();

        AdminRefreshToken entidad = adminRefreshTokenRepository
                .findByTokenHash(sha256Hex(raw)).orElseThrow();
        entidad.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        adminRefreshTokenRepository.save(entidad);

        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 7. Logout revoca refresh
    // ========================================================================
    @Test
    @DisplayName("Logout revoca el refresh token y ya no puede usarse")
    void logoutRevocaRefresh() throws Exception {
        String email = email();
        crearAdmin(email);
        JsonNode loginBody = login(email);
        String raw = loginBody.get("refreshToken").asText();

        mockMvc.perform(post("/api/admin/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isNoContent());

        // El refresh token ya no funciona
        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 8. Logout repetido es idempotente
    // ========================================================================
    @Test
    @DisplayName("Logout repetido con el mismo token es idempotente (no falla)")
    void logoutRepetidoEsIdempotente() throws Exception {
        String email = email();
        crearAdmin(email);
        JsonNode loginBody = login(email);
        String raw = loginBody.get("refreshToken").asText();

        mockMvc.perform(post("/api/admin/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isNoContent());

        // Segundo logout no falla
        mockMvc.perform(post("/api/admin/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isNoContent());
    }

    // ========================================================================
    // 9. JWT Android no accede a /api/admin/**
    // ========================================================================
    @Test
    @DisplayName("JWT de usuario Android no puede acceder a endpoints admin protegidos")
    void jwtAndroidNoAccedeAdmin() throws Exception {
        // Crear usuario normal via registro
        String userEmail = "android" + SUF.incrementAndGet() + "@test.local";
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"normal-user" + SUF.get() + "\",\"email\":\"" + userEmail + "\",\"password\":\"Pass123\"}"))
                .andExpect(status().isCreated());

        // Login para obtener JWT normal
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + userEmail + "\",\"password\":\"Pass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String normalToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/admin/tareas")
                        .header("Authorization", "Bearer " + normalToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 10. JWT admin no accede a endpoints Android
    // ========================================================================
    @Test
    @DisplayName("JWT admin no puede acceder a endpoints de usuario Android")
    void jwtAdminNoAccedeAndroid() throws Exception {
        String email = email();
        crearAdmin(email);
        JsonNode loginBody = login(email);
        String adminToken = loginBody.get("token").asText();

        // Intentar acceder a /tareas con token admin
        mockMvc.perform(get("/tareas")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 11. JWT admin con audience incorrecta falla
    // ========================================================================
    @Test
    @DisplayName("JWT con audience incorrecta es rechazado")
    void jwtConAudienceIncorrectaFalla() throws Exception {
        String email = email();
        crearAdmin(email);

        // Crear token con audience de Android
        String forgedToken = Jwts.builder()
                .subject(email)
                .issuer(adminJwtIssuer)
                .audience().add("tareas-app").and()  // audience incorrecta
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(adminTestKey())
                .compact();

        mockMvc.perform(get("/api/admin/tareas")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 12. JWT admin firmado con secret normal falla
    // ========================================================================
    @Test
    @DisplayName("JWT firmado con secret de Android es rechazado por admin")
    void jwtFirmadoConSecretNormalFalla() throws Exception {
        String email = email();
        crearAdmin(email);

        // Crear token usando el secret NORMAL (Android)
        String normalSecret = "dGFyZWFzLWFwcC10ZXN0LXNlY3JldC1nZW5lcmljby0yMDI2LXBhcmEtdGVzdHMtdW5pdGFyaW9z";
        SecretKey normalKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(normalSecret));

        String forgedToken = Jwts.builder()
                .subject(email)
                .issuer(adminJwtIssuer)
                .audience().add(adminJwtAudience).and()
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(normalKey)
                .compact();

        mockMvc.perform(get("/api/admin/tareas")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // Refresh token inexistente -> 401
    // ========================================================================
    @Test
    @DisplayName("Refresh con token inexistente falla de forma controlada")
    void refreshConTokenInexistenteFalla() throws Exception {
        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"token-aleatorio-que-no-existe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token de refresco inválido o expirado"));
    }

    // ========================================================================
    // Login sin campos -> 400
    // ========================================================================
    @Test
    @DisplayName("Login sin campos requeridos devuelve 400")
    void loginSinCamposDevuelve400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ========================================================================
    // Hash solo se almacena en BD, nunca el token en claro
    // ========================================================================
    @Test
    @DisplayName("La BD almacena solo el hash del admin refresh token, nunca el token en claro")
    void persistenciaSoloGuardaHash() throws Exception {
        String email = email();
        crearAdmin(email);
        JsonNode loginBody = login(email);
        String raw = loginBody.get("refreshToken").asText();
        String hashEsperado = sha256Hex(raw);

        AdminRefreshToken entidad = adminRefreshTokenRepository.findByTokenHash(hashEsperado).orElseThrow();

        assertThat(entidad.getTokenHash()).isEqualTo(hashEsperado);
        assertThat(entidad.getTokenHash()).isNotEqualTo(raw);
        assertThat(adminRefreshTokenRepository.findByTokenHash(raw)).isEmpty();
    }

    // ========================================================================
    // SECURITY CHAIN SEPARATION
    // ========================================================================

    @Test
    @DisplayName("GET /api/admin/lo-que-sea sin token => 401 (admin chain)")
    void adminEndpointSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/admin/lo-que-sea"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/lo-que-sea con JWT Android => 401 (admin chain rechaza)")
    void adminEndpointConJwtAndroidDevuelve401() throws Exception {
        String userEmail = "chain-android" + SUF.incrementAndGet() + "@test.local";
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

        mockMvc.perform(get("/api/admin/lo-que-sea")
                        .header("Authorization", "Bearer " + normalToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/lo-que-sea con JWT admin válido => 404 (pasa seguridad, endpoint no existe)")
    void adminEndpointConJwtAdminValidoDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        JsonNode loginBody = login(admEmail);
        String adminToken = loginBody.get("token").asText();

        mockMvc.perform(get("/api/admin/lo-que-sea")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("JWT admin válido => GET /tareas => 401 (normal chain rechaza)")
    void jwtAdminNoAccedeATareas() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        JsonNode loginBody = login(admEmail);
        String adminToken = loginBody.get("token").asText();

        mockMvc.perform(get("/tareas")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT Android válido => endpoint Android existente => sigue funcionando")
    void jwtAndroidFuncionaEnEndpointsNormales() throws Exception {
        String userEmail = "chain-ok" + SUF.incrementAndGet() + "@test.local";
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

        mockMvc.perform(get("/tareas")
                        .header("Authorization", "Bearer " + normalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
