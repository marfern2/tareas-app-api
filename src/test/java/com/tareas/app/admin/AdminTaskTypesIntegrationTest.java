package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminTaskTypesIntegrationTest {

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
        return "admin-types" + SUF.incrementAndGet() + "@test.local";
    }

    private String userEmail() {
        return "user-types" + SUF.incrementAndGet() + "@test.local";
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

    private TipoTarea crearTipoTarea(Usuario usuario, String nombre, String color) {
        TipoTarea tipo = TipoTarea.builder()
                .nombre(nombre)
                .color(color)
                .usuario(usuario)
                .build();
        return tipoTareaRepository.save(tipo);
    }

    private Tarea crearTarea(Usuario usuario, TipoTarea tipo, String titulo) {
        Tarea tarea = Tarea.builder()
                .titulo(titulo)
                .fecha(LocalDate.now())
                .completada(false)
                .urgencia(0)
                .usuario(usuario)
                .tipoTarea(tipo)
                .build();
        return tareaRepository.save(tarea);
    }

    // ========================================================================
    // 19. listado tipos -> 200
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/task-types devuelve 200 con listado")
    void listarTiposDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "type-user-" + SUF.get());
        crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(get("/api/admin/task-types")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    // ========================================================================
    // 20. búsqueda
    // ========================================================================
    @Test
    @DisplayName("Búsqueda por nombre de tipo funciona correctamente")
    void busquedaPorNombre() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "search-type-" + SUF.get());
        String uniqueName = "Findme-tipo-" + SUF.incrementAndGet();
        crearTipoTarea(usuario, uniqueName, "#FF5733");
        crearTipoTarea(usuario, "Otro tipo comun-" + SUF.get(), "#33FF57");

        MvcResult result = mockMvc.perform(get("/api/admin/task-types")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", uniqueName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("nombre").asText()).isEqualTo(uniqueName);
    }

    // ========================================================================
    // 21. paginación
    // ========================================================================
    @Test
    @DisplayName("Listado de tipos soporta paginación correcta")
    void listadoPaginado() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "pag-type-" + SUF.get());
        for (int i = 0; i < 5; i++) {
            crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        }

        mockMvc.perform(get("/api/admin/task-types")
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
    // 22. detalle existente
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/task-types/{id} tipo existente devuelve 200")
    void detalleTipoExistenteDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "detail-type-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-detalle-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(get("/api/admin/task-types/{id}", tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tipo.getId()))
                .andExpect(jsonPath("$.nombre").value(tipo.getNombre()))
                .andExpect(jsonPath("$.usuarioId").value(usuario.getId()));
    }

    // ========================================================================
    // 23. detalle inexistente -> 404
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/task-types/{id} tipo inexistente devuelve 404")
    void detalleTipoInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/task-types/{id}", 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ========================================================================
    // 24. taskCount correcto
    // ========================================================================
    @Test
    @DisplayName("taskCount refleja correctamente el número de tareas del tipo")
    void taskCountCorrecto() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "count-type-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-count-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Tarea 1");
        crearTarea(usuario, tipo, "Tarea 2");
        crearTarea(usuario, tipo, "Tarea 3");

        MvcResult result = mockMvc.perform(get("/api/admin/task-types/{id}", tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("taskCount").asLong()).isEqualTo(3);
    }

    // ========================================================================
    // 25. tipo sin tareas aparece con count 0
    // ========================================================================
    @Test
    @DisplayName("Tipo sin tareas aparece con taskCount=0")
    void tipoSinTareasApareceConCountCero() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "zero-type-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-zero-" + SUF.incrementAndGet(), "#FF5733");

        MvcResult result = mockMvc.perform(get("/api/admin/task-types")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        boolean found = false;
        for (JsonNode tipoNode : content) {
            if (tipoNode.get("id").asLong() == tipo.getId()) {
                found = true;
                assertThat(tipoNode.get("taskCount").asLong()).isZero();
                break;
            }
        }
        assertThat(found).isTrue();
    }

    // ========================================================================
    // 26. JWT admin sigue sin servir en endpoints Android
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
    // 27. JWT Android sigue funcionando en Android
    // ========================================================================
    @Test
    @DisplayName("JWT Android sigue funcionando en endpoints Android")
    void jwtAndroidFuncionaEnEndpointsNormales() throws Exception {
        String androidEmail = userEmail();
        String androidToken = loginUsuario(androidEmail);

        mockMvc.perform(get("/tareas")
                        .header("Authorization", "Bearer " + androidToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
