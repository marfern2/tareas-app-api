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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    // ========================================================================
    // CREAR TIPO
    // ========================================================================

    // Test 1
    @Test
    @DisplayName("POST /api/admin/users/{id}/task-types crea tipo válido -> 201")
    void crearTipoValidoDevuelve201() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "create-type-" + SUF.get());

        mockMvc.perform(post("/api/admin/users/{id}/task-types", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Trabajo\",\"color\":\"#FF5733\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.nombre").value("Trabajo"))
                .andExpect(jsonPath("$.color").value("#FF5733"))
                .andExpect(jsonPath("$.usuarioId").value(usuario.getId()))
                .andExpect(jsonPath("$.taskCount").value(0));
    }

    // Test 2
    @Test
    @DisplayName("POST crear tipo con descripción y color -> 201")
    void crearTipoConDescripcionDevuelve201() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "create-type-desc-" + SUF.get());

        mockMvc.perform(post("/api/admin/users/{id}/task-types", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Personal\",\"descripcion\":\"Tareas personales\",\"color\":\"#33FF57\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Personal"))
                .andExpect(jsonPath("$.descripcion").value("Tareas personales"))
                .andExpect(jsonPath("$.color").value("#33FF57"));
    }

    // Test 3
    @Test
    @DisplayName("POST crear tipo para usuario inexistente -> 404")
    void crearTipoUsuarioInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(post("/api/admin/users/{id}/task-types", 999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Trabajo\",\"color\":\"#FF5733\"}"))
                .andExpect(status().isNotFound());
    }

    // Test 4
    @Test
    @DisplayName("POST crear tipo con nombre inválido -> 400")
    void crearTipoNombreInvalidoDevuelve400() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "create-type-inv-" + SUF.get());

        mockMvc.perform(post("/api/admin/users/{id}/task-types", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"A\",\"color\":\"#FF5733\"}"))
                .andExpect(status().isBadRequest());
    }

    // Test 5
    @Test
    @DisplayName("POST crear tipo sin JWT -> 401")
    void crearTipoSinJwtDevuelve401() throws Exception {
        Usuario usuario = crearUsuario(userEmail(), "create-type-noauth-" + SUF.get());

        mockMvc.perform(post("/api/admin/users/{id}/task-types", usuario.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Trabajo\",\"color\":\"#FF5733\"}"))
                .andExpect(status().isUnauthorized());
    }

    // Test 6
    @Test
    @DisplayName("POST crear tipo con JWT Android rechazado -> 401")
    void crearTipoJwtAndroidDevuelve401() throws Exception {
        String androidEmail = userEmail();
        String androidToken = loginUsuario(androidEmail);
        Usuario usuario = crearUsuario(userEmail(), "create-type-android-" + SUF.get());

        mockMvc.perform(post("/api/admin/users/{id}/task-types", usuario.getId())
                        .header("Authorization", "Bearer " + androidToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Trabajo\",\"color\":\"#FF5733\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // EDITAR TIPO
    // ========================================================================

    // Test 7
    @Test
    @DisplayName("PATCH editar nombre -> 200")
    void editarNombreDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "update-name-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Original-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Renombrado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Renombrado"))
                .andExpect(jsonPath("$.id").value(tipo.getId()));
    }

    // Test 8
    @Test
    @DisplayName("PATCH editar descripción -> 200")
    void editarDescripcionDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "update-desc-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-desc-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descripcion\":\"Nueva descripción\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion").value("Nueva descripción"));
    }

    // Test 9
    @Test
    @DisplayName("PATCH editar color -> 200")
    void editarColorDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "update-color-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-color-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"#00FF00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("#00FF00"));
    }

    // Test 10
    @Test
    @DisplayName("PATCH body vacío -> 400")
    void editarTipoBodyVacioDevuelve400() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "update-empty-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-empty-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // Test 11
    @Test
    @DisplayName("PATCH tipo inexistente -> 404")
    void editarTipoInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "update-notfound-" + SUF.get());

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), 999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Nuevo\"}"))
                .andExpect(status().isNotFound());
    }

    // Test 12
    @Test
    @DisplayName("PATCH tipo de otro usuario -> 404")
    void editarTipoDeOtroUsuarioDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "update-owner-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "update-other-" + SUF.get());
        TipoTarea tipoUsuario1 = crearTipoTarea(usuario1, "Tipo-u1-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario2.getId(), tipoUsuario1.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Robado\"}"))
                .andExpect(status().isNotFound());
    }

    // Test 13
    @Test
    @DisplayName("PATCH usuario inexistente -> 404")
    void editarTipoUsuarioInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", 999999L, 999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Nuevo\"}"))
                .andExpect(status().isNotFound());
    }

    // Test 14
    @Test
    @DisplayName("PATCH valores inválidos -> 400")
    void editarTipoValoresInvalidosDevuelve400() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "update-inv-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-inv-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"A\",\"color\":\"invalido\"}"))
                .andExpect(status().isBadRequest());
    }

    // ========================================================================
    // ELIMINAR TIPO
    // ========================================================================

    // Test 15
    @Test
    @DisplayName("DELETE tipo sin tareas -> 204")
    void eliminarTipoSinTareasDevuelve204() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "delete-type-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Borrable-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(tipoTareaRepository.findById(tipo.getId())).isEmpty();
    }

    // Test 16
    @Test
    @DisplayName("DELETE tipo deja de existir después de eliminar")
    void eliminarTipoDejaDeExistir() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "delete-gone-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Fantasma-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(tipoTareaRepository.existsById(tipo.getId())).isFalse();
    }

    // Test 17
    @Test
    @DisplayName("DELETE tipo con tareas -> 409")
    void eliminarTipoConTareasDevuelve409() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "delete-withtasks-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "ConTareas-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Tarea asociada");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    // Test 18
    @Test
    @DisplayName("DELETE tipo con tareas no borra las tareas")
    void eliminarTipoConTareasNoBorraTareas() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "delete-nodrop-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "NoDrop-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Tarea preservada");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        assertThat(tareaRepository.findById(tarea.getId())).isPresent();
        assertThat(tipoTareaRepository.findById(tipo.getId())).isPresent();
    }

    // Test 19
    @Test
    @DisplayName("DELETE tipo de otro usuario -> 404")
    void eliminarTipoDeOtroUsuarioDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "delete-owner-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "delete-other-" + SUF.get());
        TipoTarea tipoUsuario1 = crearTipoTarea(usuario1, "Propietario-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario2.getId(), tipoUsuario1.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // Test 20
    @Test
    @DisplayName("DELETE usuario inexistente -> 404")
    void eliminarTipoUsuarioInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", 999999L, 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // Test 21
    @Test
    @DisplayName("DELETE tipo inexistente -> 404")
    void eliminarTipoInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "delete-noexist-" + SUF.get());

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // Test 22
    @Test
    @DisplayName("DELETE tipo sin JWT -> 401")
    void eliminarTipoSinJwtDevuelve401() throws Exception {
        Usuario usuario = crearUsuario(userEmail(), "delete-noauth-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "NoAuth-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId()))
                .andExpect(status().isUnauthorized());
    }

    // Test 23
    @Test
    @DisplayName("DELETE tipo con JWT Android rechazado -> 401")
    void eliminarTipoJwtAndroidDevuelve401() throws Exception {
        String androidEmail = userEmail();
        String androidToken = loginUsuario(androidEmail);
        Usuario usuario = crearUsuario(userEmail(), "delete-android-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "AndroidJWT-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + androidToken))
                .andExpect(status().isUnauthorized());
    }

    // Test 24
    @Test
    @DisplayName("DELETE tipo con JWT admin permitido -> 204")
    void eliminarTipoJwtAdminPermitido() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "delete-adminok-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "AdminOK-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(delete("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    // ========================================================================
    // VERIFICACIÓN DTO COMPLETO - POST
    // ========================================================================

    // Test 25
    @Test
    @DisplayName("POST devuelve usuarioId, usuarioUsername, usuarioEmail, taskCount=0")
    void crearTipoDevuelveDtoCompleto() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uniqueUsername = "dto-verify-" + SUF.get();
        Usuario usuario = crearUsuario(userEmail(), uniqueUsername);

        mockMvc.perform(post("/api/admin/users/{id}/task-types", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Verificado\",\"color\":\"#AABBCC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuarioId").value(usuario.getId()))
                .andExpect(jsonPath("$.usuarioUsername").value(uniqueUsername))
                .andExpect(jsonPath("$.usuarioEmail").value(usuario.getEmail()))
                .andExpect(jsonPath("$.taskCount").value(0));
    }

    // ========================================================================
    // VERIFICACIÓN DTO COMPLETO - PATCH
    // ========================================================================

    // Test 26
    @Test
    @DisplayName("PATCH devuelve usuarioId, usuarioUsername, usuarioEmail y taskCount correcto")
    void actualizarTipoDevuelveDtoCompleto() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uniqueUsername = "patch-verify-" + SUF.get();
        Usuario usuario = crearUsuario(userEmail(), uniqueUsername);
        TipoTarea tipo = crearTipoTarea(usuario, "PatchVerify-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"PatchVerificado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(usuario.getId()))
                .andExpect(jsonPath("$.usuarioUsername").value(uniqueUsername))
                .andExpect(jsonPath("$.usuarioEmail").value(usuario.getEmail()))
                .andExpect(jsonPath("$.taskCount").value(0));
    }

    // Test 27
    @Test
    @DisplayName("PATCH mantiene taskCount correcto cuando el tipo ya tiene tareas")
    void actualizarTipoMantieneTaskCount() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "patch-count-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "CountVerify-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Tarea 1");
        crearTarea(usuario, tipo, "Tarea 2");

        mockMvc.perform(patch("/api/admin/users/{id}/task-types/{taskTypeId}", usuario.getId(), tipo.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descripcion\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCount").value(2));
    }
}
