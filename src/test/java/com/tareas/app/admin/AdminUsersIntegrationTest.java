package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.RefreshTokenRepository;
import com.tareas.app.repository.UsuarioRepository;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUsersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminRefreshTokenRepository adminRefreshTokenRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private TareaRepository tareaRepository;

    @Autowired
    private TipoTareaRepository tipoTareaRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String ADMIN_PASSWORD = "Admin123";
    private static final String USER_PASSWORD = "Pass123";
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
        return "admin-users" + SUF.incrementAndGet() + "@test.local";
    }

    private String userEmail() {
        return "user-u" + SUF.incrementAndGet() + "@test.local";
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

    private Usuario crearUsuario(String email, String username) {
        Usuario usuario = Usuario.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(USER_PASSWORD))
                .build();
        return usuarioRepository.save(usuario);
    }

    private String loginUsuario(String email) throws Exception {
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reg-user" + SUF.get() + "\",\"email\":\"" + email + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private void crearTareasParaUsuario(Usuario usuario, int cantidad) {
        var tipoTarea = com.tareas.app.model.TipoTarea.builder()
                .nombre("Tipo-" + SUF.incrementAndGet())
                .color("#FF5733")
                .usuario(usuario)
                .build();
        tipoTarea = tipoTareaRepository.save(tipoTarea);

        for (int i = 0; i < cantidad; i++) {
            var tarea = com.tareas.app.model.Tarea.builder()
                    .titulo("Tarea-" + SUF.incrementAndGet() + "-" + i)
                    .fecha(java.time.LocalDate.now().plusDays(i))
                    .completada(i % 2 == 0)
                    .urgencia(0)
                    .usuario(usuario)
                    .tipoTarea(tipoTarea)
                    .build();
            tareaRepository.save(tarea);
        }
    }

    // ========================================================================
    // 1. GET /api/admin/users sin token -> 401
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/users sin token devuelve 401")
    void listarUsuariosSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 2. JWT Android -> /api/admin/users -> 401
    // ========================================================================
    @Test
    @DisplayName("JWT Android no puede acceder a GET /api/admin/users")
    void jwtAndroidNoAccedeListarUsuarios() throws Exception {
        String androidEmail = userEmail();
        String androidToken = loginUsuario(androidEmail);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + androidToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 3. JWT admin -> listado -> 200
    // ========================================================================
    @Test
    @DisplayName("JWT admin puede listar usuarios -> 200")
    void jwtAdminPuedeListarUsuarios() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    // ========================================================================
    // 4. Listado paginado
    // ========================================================================
    @Test
    @DisplayName("Listado de usuarios soporta paginación correcta")
    void listadoPaginado() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        for (int i = 0; i < 5; i++) {
            crearUsuario(userEmail(), "pag-user-" + SUF.get());
        }

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    // ========================================================================
    // 5. Búsqueda por username
    // ========================================================================
    @Test
    @DisplayName("Búsqueda por username funciona correctamente")
    void busquedaPorUsername() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String targetUsername = "findme-unico-" + SUF.incrementAndGet();
        crearUsuario(userEmail(), targetUsername);
        crearUsuario(userEmail(), "otro-usuario-" + SUF.get());

        MvcResult result = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", targetUsername))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode user : content) {
            if (user.get("username").asText().equals(targetUsername)) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    // ========================================================================
    // 6. Búsqueda por email
    // ========================================================================
    @Test
    @DisplayName("Búsqueda por email funciona correctamente")
    void busquedaPorEmail() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String targetEmail = "findme-" + SUF.incrementAndGet() + "@test.local";
        crearUsuario(targetEmail, "email-find-" + SUF.get());
        crearUsuario(userEmail(), "otro-" + SUF.get());

        MvcResult result = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", targetEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("email").asText()).isEqualTo(targetEmail);
    }

    // ========================================================================
    // 7. Usuario existente -> 200
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/users/{id} usuario existente devuelve 200")
    void detalleUsuarioExistenteDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "detail-user-" + SUF.get());

        mockMvc.perform(get("/api/admin/users/{id}", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuario.getId()))
                .andExpect(jsonPath("$.username").value(usuario.getUsername()))
                .andExpect(jsonPath("$.email").value(uEmail));
    }

    // ========================================================================
    // 8. Usuario inexistente -> 404
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/users/{id} usuario inexistente devuelve 404")
    void detalleUsuarioInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/users/{id}", 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ========================================================================
    // 9. Detalle no expone password
    // ========================================================================
    @Test
    @DisplayName("Detalle de usuario no expone password ni hash")
    void detalleNoExponePassword() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "nosecret-" + SUF.get());

        MvcResult result = mockMvc.perform(get("/api/admin/users/{id}", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("passwordHash");
    }

    // ========================================================================
    // 10. Detalle calcula contadores correctamente
    // ========================================================================
    @Test
    @DisplayName("Detalle calcula contadores de tareas correctamente")
    void detalleCalculaContadores() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "count-user-" + SUF.get());
        crearTareasParaUsuario(usuario, 3);

        MvcResult result = mockMvc.perform(get("/api/admin/users/{id}", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("totalTasks").asLong()).isEqualTo(3);
        assertThat(body.get("completedTasks").asLong()).isEqualTo(2);
        assertThat(body.get("pendingTasks").asLong()).isEqualTo(1);
        assertThat(body.get("taskTypeCount").asLong()).isGreaterThanOrEqualTo(1);
    }

    // ========================================================================
    // 11. /users/{id}/tasks devuelve solo tareas de ese usuario
    // ========================================================================
    @Test
    @DisplayName("/users/{id}/tasks devuelve solo las tareas del usuario indicado")
    void tareasUsuarioDevuelveSoloTareasDeEseUsuario() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail1 = userEmail();
        Usuario usuario1 = crearUsuario(uEmail1, "tarea-owner-" + SUF.get());
        crearTareasParaUsuario(usuario1, 2);

        String uEmail2 = userEmail();
        Usuario usuario2 = crearUsuario(uEmail2, "tarea-other-" + SUF.get());
        crearTareasParaUsuario(usuario2, 3);

        MvcResult result = mockMvc.perform(get("/api/admin/users/{id}/tasks", usuario1.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(2);

        for (JsonNode tarea : content) {
            assertThat(tarea.get("titulo")).isNotNull();
            assertThat(tarea.get("fecha")).isNotNull();
            assertThat(tarea.get("completada")).isNotNull();
            assertThat(tarea.get("urgencia")).isNotNull();
        }
    }

    // ========================================================================
    // 12. /users/{id}/tasks paginado
    // ========================================================================
    @Test
    @DisplayName("/users/{id}/tasks soporta paginación")
    void tareasUsuarioPaginado() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "pag-tasks-" + SUF.get());
        crearTareasParaUsuario(usuario, 5);

        mockMvc.perform(get("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    // ========================================================================
    // 13. Usuario inexistente en /tasks -> 404
    // ========================================================================
    @Test
    @DisplayName("/users/{id}/tasks con usuario inexistente devuelve 404")
    void tareasUsuarioInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/users/{id}/tasks", 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ========================================================================
    // 14. JWT admin sigue sin servir para endpoints Android normales
    // ========================================================================
    @Test
    @DisplayName("JWT admin no puede acceder a endpoints Android normales")
    void jwtAdminNoAccedeEndpointsAndroid() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/tareas")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/usuarios/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 15. Usuario sin tareas aparece en listado con counts 0
    // ========================================================================
    @Test
    @DisplayName("Usuario sin tareas aparece en listado con taskCount=0 y taskTypeCount=0")
    void usuarioSinTareasApareceConCountsCero() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "zero-tasks-" + SUF.get());

        MvcResult result = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        boolean found = false;
        for (JsonNode user : content) {
            if (user.get("id").asLong() == usuario.getId()) {
                found = true;
                assertThat(user.get("taskCount").asLong()).isZero();
                assertThat(user.get("taskTypeCount").asLong()).isZero();
                break;
            }
        }
        assertThat(found).isTrue();
    }

    // ========================================================================
    // 16. Paginación: página 1 size 2 con totalElements correcto
    // ========================================================================
    @Test
    @DisplayName("Paginación page=0 size=2 y page=1 size=2 con totalElements consistente")
    void paginacionPage0YPage1() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        for (int i = 0; i < 5; i++) {
            crearUsuario(userEmail(), "pag2-user-" + SUF.get());
        }

        MvcResult result0 = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andReturn();

        JsonNode body0 = objectMapper.readTree(result0.getResponse().getContentAsString());
        long totalElements = body0.get("totalElements").asLong();
        int totalPages = body0.get("totalPages").asLong() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : body0.get("totalPages").asInt();
        assertThat(totalElements).isGreaterThanOrEqualTo(5);
        assertThat(totalPages).isGreaterThanOrEqualTo(3);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false));
    }

    // ========================================================================
    // 17. Búsqueda sin resultados devuelve lista vacía
    // ========================================================================
    @Test
    @DisplayName("Búsqueda sin resultados devuelve lista vacía con totalElements=0")
    void busquedaSinResultados() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        crearUsuario(userEmail(), "existing-user-" + SUF.get());

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "zzz-no-existe-" + SUF.incrementAndGet()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ========================================================================
    // 18. Sort username asc/desc
    // ========================================================================
    @Test
    @DisplayName("Sort por username asc y desc funciona correctamente")
    void sortUsernameAscDesc() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        crearUsuario(userEmail(), "zzz-last-" + SUF.get());
        crearUsuario(userEmail(), "aaa-first-" + SUF.get());

        MvcResult resultAsc = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "username,asc"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode contentAsc = objectMapper.readTree(resultAsc.getResponse().getContentAsString()).get("content");
        String firstAsc = contentAsc.get(0).get("username").asText();
        assertThat(firstAsc).startsWith("aaa-first-");

        MvcResult resultDesc = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "username,desc"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode contentDesc = objectMapper.readTree(resultDesc.getResponse().getContentAsString()).get("content");
        String firstDesc = contentDesc.get(0).get("username").asText();
        assertThat(firstDesc).startsWith("zzz-last-");
    }

    // ========================================================================
    // 19. Sort inválido no causa 500
    // ========================================================================
    @Test
    @DisplayName("Sort inválido usa fallback a id sin causar 500")
    void sortInvalidoNoCausa500() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "password,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "nonexistent-field"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ========================================================================
    // 20. Detalle: completada=null se cuenta como pending
    // ========================================================================
    @Test
    @DisplayName("Detalle calcula contadores cuando completada puede ser null")
    void detalleContadoresConCompletadaNull() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "null-compl-" + SUF.get());

        var tipoTarea = com.tareas.app.model.TipoTarea.builder()
                .nombre("TipoNull-" + SUF.incrementAndGet())
                .color("#AABBCC")
                .usuario(usuario)
                .build();
        tipoTarea = tipoTareaRepository.save(tipoTarea);

        tareaRepository.save(com.tareas.app.model.Tarea.builder()
                .titulo("Tarea-true")
                .fecha(java.time.LocalDate.now())
                .completada(true)
                .urgencia(0)
                .usuario(usuario)
                .tipoTarea(tipoTarea)
                .build());

        tareaRepository.save(com.tareas.app.model.Tarea.builder()
                .titulo("Tarea-null")
                .fecha(java.time.LocalDate.now())
                .completada(null)
                .urgencia(0)
                .usuario(usuario)
                .tipoTarea(tipoTarea)
                .build());

        MvcResult result = mockMvc.perform(get("/api/admin/users/{id}", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("totalTasks").asLong()).isEqualTo(2);
        assertThat(body.get("completedTasks").asLong()).isEqualTo(1);
        assertThat(body.get("pendingTasks").asLong()).isEqualTo(1);
    }
}
