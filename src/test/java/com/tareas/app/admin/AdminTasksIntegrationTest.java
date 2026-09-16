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
class AdminTasksIntegrationTest {

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
        return "admin-tasks" + SUF.incrementAndGet() + "@test.local";
    }

    private String userEmail() {
        return "user-tasks" + SUF.incrementAndGet() + "@test.local";
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

    private Tarea crearTarea(Usuario usuario, TipoTarea tipo, String titulo, LocalDate fecha,
                             Boolean completada, Integer urgencia) {
        Tarea tarea = Tarea.builder()
                .titulo(titulo)
                .fecha(fecha)
                .completada(completada)
                .urgencia(urgencia)
                .usuario(usuario)
                .tipoTarea(tipo)
                .build();
        return tareaRepository.save(tarea);
    }

    // ========================================================================
    // 1. sin token -> 401
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/tasks sin token devuelve 401")
    void listarTareasSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/admin/tasks"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 2. JWT Android -> 401
    // ========================================================================
    @Test
    @DisplayName("JWT Android no puede acceder a GET /api/admin/tasks")
    void jwtAndroidNoAccedeListarTareas() throws Exception {
        String androidToken = loginUsuario(userEmail());

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + androidToken))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 3. JWT admin -> listado 200
    // ========================================================================
    @Test
    @DisplayName("JWT admin puede listar tareas -> 200")
    void jwtAdminPuedeListarTareas() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "task-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Tarea test", LocalDate.now(), false, 0);

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    // ========================================================================
    // 4. paginación
    // ========================================================================
    @Test
    @DisplayName("Listado de tareas soporta paginación correcta")
    void listadoPaginado() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "pag-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        for (int i = 0; i < 5; i++) {
            crearTarea(usuario, tipo, "Tarea-" + i, LocalDate.now().plusDays(i), false, 0);
        }

        mockMvc.perform(get("/api/admin/tasks")
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
    // 5. search titulo
    // ========================================================================
    @Test
    @DisplayName("Búsqueda por título funciona correctamente")
    void busquedaPorTitulo() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "search-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        String uniqueTitle = "Findme-unico-" + SUF.incrementAndGet();
        crearTarea(usuario, tipo, uniqueTitle, LocalDate.now(), false, 0);
        crearTarea(usuario, tipo, "Otra tarea comun", LocalDate.now(), false, 0);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", uniqueTitle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("titulo").asText()).isEqualTo(uniqueTitle);
    }

    // ========================================================================
    // 6. search descripcion
    // ========================================================================
    @Test
    @DisplayName("Búsqueda por descripción funciona correctamente")
    void busquedaPorDescripcion() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "desc-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        String uniqueDesc = "desc-busqueda-" + SUF.incrementAndGet();
        Tarea tarea = Tarea.builder()
                .titulo("Tarea con desc especial")
                .descripcion(uniqueDesc)
                .fecha(LocalDate.now())
                .completada(false)
                .urgencia(0)
                .usuario(usuario)
                .tipoTarea(tipo)
                .build();
        tareaRepository.save(tarea);

        crearTarea(usuario, tipo, "Otra tarea", LocalDate.now(), false, 0);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", uniqueDesc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("descripcion").asText()).isEqualTo(uniqueDesc);
    }

    // ========================================================================
    // 7. filtro userId
    // ========================================================================
    @Test
    @DisplayName("Filtro por userId devuelve solo tareas de ese usuario")
    void filtroUserId() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "owner-1-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "owner-2-" + SUF.get());
        TipoTarea tipo1 = crearTipoTarea(usuario1, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        TipoTarea tipo2 = crearTipoTarea(usuario2, "Tipo-" + SUF.incrementAndGet(), "#33FF57");

        crearTarea(usuario1, tipo1, "Tarea user1", LocalDate.now(), false, 0);
        crearTarea(usuario1, tipo1, "Tarea user1-2", LocalDate.now(), false, 0);
        crearTarea(usuario2, tipo2, "Tarea user2", LocalDate.now(), false, 0);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("userId", usuario1.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(2);
        for (JsonNode tarea : content) {
            assertThat(tarea.get("usuarioId").asLong()).isEqualTo(usuario1.getId());
        }
    }

    // ========================================================================
    // 8. filtro completed=true
    // ========================================================================
    @Test
    @DisplayName("Filtro completed=true devuelve solo tareas completadas")
    void filtroCompletedTrue() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "compl-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Completada", LocalDate.now(), true, 0);
        crearTarea(usuario, tipo, "Pendiente", LocalDate.now(), false, 0);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("completed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("completada").asBoolean()).isTrue();
    }

    // ========================================================================
    // 9. filtro completed=false (incluye NULL)
    // ========================================================================
    @Test
    @DisplayName("Filtro completed=false incluye FALSE y NULL (pendientes)")
    void filtroCompletedFalseIncluyeNull() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "null-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Completada", LocalDate.now(), true, 0);
        Tarea tareaFalse = crearTarea(usuario, tipo, "False", LocalDate.now(), false, 0);
        Tarea tareaNull = Tarea.builder()
                .titulo("Null-completada")
                .fecha(LocalDate.now())
                .completada(null)
                .urgencia(0)
                .usuario(usuario)
                .tipoTarea(tipo)
                .build();
        tareaRepository.save(tareaNull);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("completed", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(2);

        boolean foundFalse = false;
        boolean foundNull = false;
        for (JsonNode tarea : content) {
            if (tarea.get("id").asLong() == tareaFalse.getId()) foundFalse = true;
            if (tarea.get("id").asLong() == tareaNull.getId()) foundNull = true;
        }
        assertThat(foundFalse).isTrue();
        assertThat(foundNull).isTrue();
    }

    // ========================================================================
    // 10. filtro urgency
    // ========================================================================
    @Test
    @DisplayName("Filtro por urgencia funciona correctamente")
    void filtroUrgency() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "urg-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "urg0", LocalDate.now(), false, 0);
        crearTarea(usuario, tipo, "urg1", LocalDate.now(), false, 1);
        crearTarea(usuario, tipo, "urg2", LocalDate.now(), false, 2);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("urgency", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("urgencia").asInt()).isEqualTo(2);
    }

    // ========================================================================
    // 11. filtro taskTypeId
    // ========================================================================
    @Test
    @DisplayName("Filtro por taskTypeId funciona correctamente")
    void filtroTaskTypeId() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "type-user-" + SUF.get());
        TipoTarea tipo1 = crearTipoTarea(usuario, "TipoA-" + SUF.incrementAndGet(), "#FF5733");
        TipoTarea tipo2 = crearTipoTarea(usuario, "TipoB-" + SUF.incrementAndGet(), "#33FF57");
        crearTarea(usuario, tipo1, "Tarea tipo1", LocalDate.now(), false, 0);
        crearTarea(usuario, tipo1, "Tarea tipo1-2", LocalDate.now(), false, 0);
        crearTarea(usuario, tipo2, "Tarea tipo2", LocalDate.now(), false, 0);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("taskTypeId", tipo1.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(2);
        for (JsonNode tarea : content) {
            assertThat(tarea.get("tipoTareaId").asLong()).isEqualTo(tipo1.getId());
        }
    }

    // ========================================================================
    // 12. filtros combinados
    // ========================================================================
    @Test
    @DisplayName("Filtros combinados funcionan correctamente")
    void filtrosCombinados() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "combo-1-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "combo-2-" + SUF.get());
        TipoTarea tipo1 = crearTipoTarea(usuario1, "TipoA-" + SUF.incrementAndGet(), "#FF5733");
        TipoTarea tipo2 = crearTipoTarea(usuario2, "TipoB-" + SUF.incrementAndGet(), "#33FF57");

        crearTarea(usuario1, tipo1, "Match combo", LocalDate.now(), false, 1);
        crearTarea(usuario1, tipo1, "No match title", LocalDate.now(), true, 1);
        crearTarea(usuario2, tipo2, "Match combo", LocalDate.now(), false, 1);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "Match combo")
                        .param("userId", usuario1.getId().toString())
                        .param("completed", "false")
                        .param("urgency", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("titulo").asText()).isEqualTo("Match combo");
        assertThat(content.get(0).get("usuarioId").asLong()).isEqualTo(usuario1.getId());
    }

    // ========================================================================
    // 13. sort asc/desc
    // ========================================================================
    @Test
    @DisplayName("Sort por fecha asc y desc funciona correctamente")
    void sortFechaAscDesc() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "sort-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        crearTarea(usuario, tipo, "Tarea hoy", LocalDate.now(), false, 0);
        crearTarea(usuario, tipo, "Tarea manana", LocalDate.now().plusDays(1), false, 0);
        crearTarea(usuario, tipo, "Tarea ayer", LocalDate.now().minusDays(1), false, 0);

        MvcResult resultAsc = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "fecha,asc"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode contentAsc = objectMapper.readTree(resultAsc.getResponse().getContentAsString()).get("content");
        assertThat(contentAsc.get(0).get("titulo").asText()).isEqualTo("Tarea ayer");

        MvcResult resultDesc = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "fecha,desc"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode contentDesc = objectMapper.readTree(resultDesc.getResponse().getContentAsString()).get("content");
        assertThat(contentDesc.get(0).get("titulo").asText()).isEqualTo("Tarea manana");
    }

    // ========================================================================
    // 14. sort inválido no causa 500
    // ========================================================================
    @Test
    @DisplayName("Sort inválido usa fallback a fecha desc sin causar 500")
    void sortInvalidoNoCausa500() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "password,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sort", "nonexistent-field"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ========================================================================
    // 15. detalle existente -> 200
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/tasks/{id} tarea existente devuelve 200")
    void detalleTareaExistenteDevuelve200() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "detail-user-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Tarea detalle", LocalDate.now(), false, 1);

        mockMvc.perform(get("/api/admin/tasks/{id}", tarea.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tarea.getId()))
                .andExpect(jsonPath("$.titulo").value("Tarea detalle"))
                .andExpect(jsonPath("$.usuarioId").value(usuario.getId()))
                .andExpect(jsonPath("$.tipoTareaId").value(tipo.getId()));
    }

    // ========================================================================
    // 16. detalle inexistente -> 404
    // ========================================================================
    @Test
    @DisplayName("GET /api/admin/tasks/{id} tarea inexistente devuelve 404")
    void detalleTareaInexistenteDevuelve404() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(get("/api/admin/tasks/{id}", 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ========================================================================
    // 17. DTO no expone campos sensibles
    // ========================================================================
    @Test
    @DisplayName("Detalle de tarea no expone passwords ni hashes")
    void dtoNoExponeCamposSensibles() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario = crearUsuario(userEmail(), "nosecret-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Tarea segura", LocalDate.now(), false, 0);

        MvcResult result = mockMvc.perform(get("/api/admin/tasks/{id}", tarea.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("passwordHash");
    }

    // ========================================================================
    // 18. no mezcla tareas entre usuarios
    // ========================================================================
    @Test
    @DisplayName("Listado global incluye tareas de todos los usuarios (el filtro userId aísla)")
    void noMezclaTareasEntreUsuarios() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "iso-1-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "iso-2-" + SUF.get());
        TipoTarea tipo1 = crearTipoTarea(usuario1, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        TipoTarea tipo2 = crearTipoTarea(usuario2, "Tipo-" + SUF.incrementAndGet(), "#33FF57");

        crearTarea(usuario1, tipo1, "T1-user1", LocalDate.now(), false, 0);
        crearTarea(usuario2, tipo2, "T1-user2", LocalDate.now(), false, 0);

        // Sin filtro: ve las 2
        MvcResult all = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode allContent = objectMapper.readTree(all.getResponse().getContentAsString()).get("content");
        assertThat(allContent.size()).isEqualTo(2);

        // Con filtro userId de usuario1: ve solo 1
        MvcResult filtered = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("userId", usuario1.getId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode filteredContent = objectMapper.readTree(filtered.getResponse().getContentAsString()).get("content");
        assertThat(filteredContent.size()).isEqualTo(1);
        assertThat(filteredContent.get(0).get("usuarioId").asLong()).isEqualTo(usuario1.getId());
    }

    // ========================================================================
    // POST /api/admin/users/{id}/tasks - CREAR TAREA
    // ========================================================================

    @Test
    @DisplayName("POST /api/admin/users/{id}/tasks sin token devuelve 401")
    void crearTareaSinTokenDevuelve401() throws Exception {
        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "noauth-create-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea nueva\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT Android no puede crear tarea vía admin")
    void jwtAndroidNoPuedeCrearTareaAdmin() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String androidToken = loginUsuario(userEmail());

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "android-create-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + androidToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea nueva\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Crear tarea mínima válida -> 201")
    void crearTareaMinimaValida() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "min-create-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea minima\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.titulo").value("Tarea minima"))
                .andExpect(jsonPath("$.completada").value(false))
                .andExpect(jsonPath("$.urgencia").value(0))
                .andExpect(jsonPath("$.usuarioId").value(usuario.getId()))
                .andExpect(jsonPath("$.tipoTareaId").value(tipo.getId()));
    }

    @Test
    @DisplayName("Crear tarea con todos los campos -> 201")
    void crearTareaTodosLosCampos() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "full-create-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea completa\",\"descripcion\":\"Desc\",\"fecha\":\"2026-12-31\",\"completada\":true,\"urgencia\":2,\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titulo").value("Tarea completa"))
                .andExpect(jsonPath("$.descripcion").value("Desc"))
                .andExpect(jsonPath("$.completada").value(true))
                .andExpect(jsonPath("$.urgencia").value(2));
    }

    @Test
    @DisplayName("Crear tarea con usuario inexistente -> 404")
    void crearTareaUsuarioInexistente() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(post("/api/admin/users/{id}/tasks", 999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea x\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Crear tarea con tipo válido del mismo usuario -> 201")
    void crearTareaConTipoMismoUsuario() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "own-type-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Con tipo propio\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoTareaId").value(tipo.getId()));
    }

    @Test
    @DisplayName("Crear tarea con tipo de otro usuario -> 404")
    void crearTareaConTipoDeOtroUsuario() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "owner-a-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "owner-b-" + SUF.get());
        TipoTarea tipo2 = crearTipoTarea(usuario2, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario1.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tipo ajeno\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo2.getId() + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Crear tarea con titulo vacío -> 400")
    void crearTareaTituloVacio() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "empty-title-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"\",\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Crear tarea sin titulo -> 400")
    void crearTareaSinTitulo() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "no-title-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fecha\":\"2026-12-31\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Crear tarea sin fecha -> 400")
    void crearTareaSinFecha() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "no-date-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Sin fecha\",\"tipoTareaId\":" + tipo.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Crear tarea sin tipoTareaId -> 400")
    void crearTareaSinTipoTareaId() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "no-type-" + SUF.get());

        mockMvc.perform(post("/api/admin/users/{id}/tasks", usuario.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Sin tipo\",\"fecha\":\"2026-12-31\"}"))
                .andExpect(status().isBadRequest());
    }

    // ========================================================================
    // PATCH /api/admin/users/{id}/tasks/{taskId} - EDITAR TAREA
    // ========================================================================

    @Test
    @DisplayName("PATCH editar titulo -> 200")
    void editarTitulo() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "edit-title-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Titulo original", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Titulo nuevo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Titulo nuevo"));

        assertThat(tareaRepository.findById(tarea.getId()).get().getTitulo()).isEqualTo("Titulo nuevo");
    }

    @Test
    @DisplayName("PATCH editar descripcion -> 200")
    void editarDescripcion() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "edit-desc-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Con desc", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descripcion\":\"Nueva desc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion").value("Nueva desc"));
    }

    @Test
    @DisplayName("PATCH editar fecha -> 200")
    void editarFecha() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "edit-date-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Con fecha", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fecha\":\"2027-06-15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fecha").value("2027-06-15"));
    }

    @Test
    @DisplayName("PATCH editar completada -> 200")
    void editarCompletada() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "edit-compl-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Pendiente", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completada\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completada").value(true));

        assertThat(tareaRepository.findById(tarea.getId()).get().getCompletada()).isTrue();
    }

    @Test
    @DisplayName("PATCH editar urgencia -> 200")
    void editarUrgencia() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "edit-urg-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Baja urgencia", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urgencia\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgencia").value(2));
    }

    @Test
    @DisplayName("PATCH cambiar tipo válido -> 200")
    void cambiarTipoValido() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "change-type-" + SUF.get());
        TipoTarea tipo1 = crearTipoTarea(usuario, "TipoA-" + SUF.incrementAndGet(), "#FF5733");
        TipoTarea tipo2 = crearTipoTarea(usuario, "TipoB-" + SUF.incrementAndGet(), "#33FF57");
        Tarea tarea = crearTarea(usuario, tipo1, "Cambio tipo", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoTareaId\":" + tipo2.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoTareaId").value(tipo2.getId()));
    }

    @Test
    @DisplayName("PATCH tipo de otro usuario rechazado -> 404")
    void cambiarTipoDeOtroUsuario() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "owner-c-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "owner-d-" + SUF.get());
        TipoTarea tipo1 = crearTipoTarea(usuario1, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        TipoTarea tipo2 = crearTipoTarea(usuario2, "Tipo-" + SUF.incrementAndGet(), "#33FF57");
        Tarea tarea = crearTarea(usuario1, tipo1, "Tarea ajena", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario1.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoTareaId\":" + tipo2.getId() + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH body vacío -> 400")
    void editarTareaBodyVacio() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "empty-body-t-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Body vacio", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH tarea inexistente -> 404")
    void editarTareaInexistente() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "notfound-t-" + SUF.get());

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), 999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea nueva\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH tarea de otro usuario -> 404")
    void editarTareaDeOtroUsuario() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "owner-e-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "owner-f-" + SUF.get());
        TipoTarea tipo2 = crearTipoTarea(usuario2, "Tipo-" + SUF.incrementAndGet(), "#33FF57");
        Tarea tarea2 = crearTarea(usuario2, tipo2, "Tarea de otro", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario1.getId(), tarea2.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Robo\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH usuario inexistente -> 404")
    void editarTareaUsuarioInexistente() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", 999999L, 1L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Tarea nueva\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH titulo con solo espacios -> 400")
    void editarTareaTituloBlanco() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "blank-title-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Valida", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH titulo muy corto -> 400")
    void editarTareaTituloMuyCorto() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "short-title-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Valida titulo", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"ab\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH urgencia fuera de rango -> 400")
    void editarTareaUrgenciaFueraDeRango() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "urg-range-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Valida urg", LocalDate.now(), false, 0);

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urgencia\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH sin token -> 401")
    void patchTareaSinTokenDevuelve401() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH JWT Android -> 401")
    void patchTareaJwtAndroidDevuelve401() throws Exception {
        String androidToken = loginUsuario(userEmail());

        mockMvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", 1L, 1L)
                        .header("Authorization", "Bearer " + androidToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // DELETE /api/admin/users/{id}/tasks/{taskId} - ELIMINAR TAREA
    // ========================================================================

    @Test
    @DisplayName("DELETE eliminar tarea -> 204")
    void eliminarTareaDevuelve204() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "delete-t-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Para borrar", LocalDate.now(), false, 0);

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(tareaRepository.existsById(tarea.getId())).isFalse();
    }

    @Test
    @DisplayName("DELETE tarea deja de existir tras eliminación")
    void eliminarTareaDejaDeExistir() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "gone-t-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Borrar y ver", LocalDate.now(), false, 0);

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/tasks/{id}", tarea.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE tarea de otro usuario -> 404")
    void eliminarTareaDeOtroUsuario() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        Usuario usuario1 = crearUsuario(userEmail(), "owner-g-" + SUF.get());
        Usuario usuario2 = crearUsuario(userEmail(), "owner-h-" + SUF.get());
        TipoTarea tipo2 = crearTipoTarea(usuario2, "Tipo-" + SUF.incrementAndGet(), "#33FF57");
        Tarea tarea2 = crearTarea(usuario2, tipo2, "Tarea protegida", LocalDate.now(), false, 0);

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", usuario1.getId(), tarea2.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        assertThat(tareaRepository.existsById(tarea2.getId())).isTrue();
    }

    @Test
    @DisplayName("DELETE usuario inexistente -> 404")
    void eliminarTareaUsuarioInexistente() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", 999999L, 1L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE tarea inexistente -> 404")
    void eliminarTareaInexistente() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "no-task-" + SUF.get());

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE sin token -> 401")
    void deleteTareaSinTokenDevuelve401() throws Exception {
        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", 1L, 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE JWT Android -> 401")
    void deleteTareaJwtAndroidDevuelve401() throws Exception {
        String androidToken = loginUsuario(userEmail());

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", 1L, 1L)
                        .header("Authorization", "Bearer " + androidToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE JWT admin -> permitido")
    void deleteTareaJwtAdminPermitido() throws Exception {
        String admEmail = email();
        crearAdmin(admEmail);
        String adminToken = loginAdmin(admEmail);

        String uEmail = userEmail();
        Usuario usuario = crearUsuario(uEmail, "admin-can-del-" + SUF.get());
        TipoTarea tipo = crearTipoTarea(usuario, "Tipo-" + SUF.incrementAndGet(), "#FF5733");
        Tarea tarea = crearTarea(usuario, tipo, "Admin borra", LocalDate.now(), false, 0);

        mockMvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", usuario.getId(), tarea.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }
}
