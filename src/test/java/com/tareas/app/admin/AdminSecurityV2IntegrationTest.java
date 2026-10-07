package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminAuditEventRepository;
import com.tareas.app.admin.repository.AdminRefreshTokenRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.admin.security.AdminUserDetails;
import com.tareas.app.admin.service.AdminUserService;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.RefreshTokenRepository;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import com.tareas.app.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityV2IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired AdminUserRepository admins;
    @Autowired AdminRefreshTokenRepository adminTokens;
    @Autowired AdminAuditEventRepository audit;
    @Autowired UsuarioRepository users;
    @Autowired TareaRepository tasks;
    @Autowired TipoTareaRepository types;
    @Autowired RefreshTokenRepository tokens;
    @Autowired AdminUserService adminUserService;

    @BeforeEach
    void clean() {
        audit.deleteAll();
        adminTokens.deleteAll();
        admins.deleteAll();
        tasks.deleteAll();
        types.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
    }

    private AdminUser admin(Set<AdminPermission> permissions) {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        return admins.save(AdminUser.builder().username("admin-" + marker)
                .email("admin-" + marker + "@example.invalid")
                .passwordHash(passwords.encode("test-only-password"))
                .enabled(true).createdAt(LocalDateTime.now()).permissions(permissions).build());
    }

    private String token(AdminUser admin) throws Exception {
        String json = mapper.writeValueAsString(java.util.Map.of(
                "email", admin.getEmail(), "password", "test-only-password"));
        String body = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    private Usuario user(boolean protectedAccount) {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        return users.save(Usuario.builder().username("user-" + marker)
                .email("user-" + marker + "@example.invalid")
                .password("hash").protectedFromAdminMutation(protectedAccount).build());
    }

    @Test
    void unauthenticatedDeniedReadOnlyCannotMutateAndWriterCanUpdate() throws Exception {
        Usuario user = user(false);
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        AdminUser reader = admin(EnumSet.of(AdminPermission.ADMIN_READ));
        String readToken = token(reader);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + readToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"blocked\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/{id}", user.getId())
                .param("permission", "USER_DELETE")
                .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isForbidden());
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(audit.findAll()).hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getOutcome()).isEqualTo("FAILURE");
                    assertThat(event.getAdminUserId()).isEqualTo(reader.getId());
                });

        AdminUser writer = admin(EnumSet.of(AdminPermission.USER_WRITE));
        String writeToken = token(writer);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + writeToken)
                .header("X-Request-ID", "security-v2-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"updated\"}"))
                .andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getUsername()).isEqualTo("updated");
        assertThat(audit.findAll()).anySatisfy(event -> {
            assertThat(event.getOperation()).isEqualTo("USER_UPDATE");
            assertThat(event.getResourceId()).isEqualTo(user.getId());
            assertThat(event.getAdminUserId()).isEqualTo(writer.getId());
            assertThat(event.getCorrelationId()).isEqualTo("security-v2-test");
        });
    }

    @Test
    void serviceAlsoRejectsReadOnlyAdministrator() {
        Usuario user = user(false);
        AdminUserDetails details = new AdminUserDetails(admin(EnumSet.of(AdminPermission.ADMIN_READ)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities()));
        try {
            assertThatThrownBy(() -> adminUserService.eliminarUsuario(user.getId()))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(users.findById(user.getId())).isPresent();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void permissionRevocationTakesEffectForAlreadyIssuedToken() throws Exception {
        Usuario user = user(false);
        AdminUser admin = admin(EnumSet.of(AdminPermission.ADMIN_READ, AdminPermission.USER_DELETE));
        String jwt = token(admin);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        AdminUser current = admins.findById(admin.getId()).orElseThrow();
        current.getPermissions().clear();
        admins.saveAndFlush(current);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden());
        assertThat(users.findById(user.getId())).isPresent();
    }

    @Test
    void readOnlyAndWrongWritePermissionsCannotReachNestedMutations() throws Exception {
        Usuario user = user(false);
        TipoTarea type = types.save(TipoTarea.builder().nombre("Existing type")
                .color("#123456").usuario(user).build());
        Tarea task = tasks.save(Tarea.builder().titulo("Existing task")
                .fecha(LocalDate.now()).usuario(user).tipoTarea(type).build());
        String reader = token(admin(EnumSet.of(AdminPermission.ADMIN_READ)));
        mvc.perform(post("/api/admin/users/{id}/tasks", user.getId())
                .header("Authorization", "Bearer " + reader).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", user.getId(), task.getId())
                .header("Authorization", "Bearer " + reader).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", user.getId(), task.getId())
                .header("Authorization", "Bearer " + reader))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/{id}/task-types", user.getId())
                .header("Authorization", "Bearer " + reader).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/{id}/task-types/{typeId}", user.getId(), type.getId())
                .header("Authorization", "Bearer " + reader).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/{id}/task-types/{typeId}", user.getId(), type.getId())
                .header("Authorization", "Bearer " + reader))
                .andExpect(status().isForbidden());
        String taskWriter = token(admin(EnumSet.of(AdminPermission.TASK_WRITE)));
        mvc.perform(patch("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + taskWriter).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"wrong\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + taskWriter))
                .andExpect(status().isForbidden());
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(tasks.findById(task.getId())).isPresent();
        assertThat(types.findById(type.getId())).isPresent();
    }

    @Test
    void requestBodyCannotRemoveUserProtection() throws Exception {
        Usuario user = user(true);
        String jwt = token(admin(EnumSet.of(AdminPermission.USER_WRITE)));
        mvc.perform(patch("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"changed\",\"protectedFromAdminMutation\":false}"))
                .andExpect(status().isConflict());
        assertThat(users.findById(user.getId()).orElseThrow().getProtectedFromAdminMutation()).isTrue();
    }

    @Test
    void protectedUserAndResourcesCannotBeDeleted() throws Exception {
        Usuario user = user(true);
        TipoTarea type = types.save(TipoTarea.builder().nombre("Type").color("#123456").usuario(user).build());
        Tarea task = tasks.save(Tarea.builder().titulo("Task").fecha(LocalDate.now())
                .usuario(user).tipoTarea(type).build());
        tokens.save(com.tareas.app.model.RefreshToken.builder().usuario(user).tokenHash("protected-session")
                .createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        String jwt = token(admin(EnumSet.allOf(AdminPermission.class)));
        mvc.perform(delete("/api/admin/users/{id}", user.getId()).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/users/{id}/tasks/{taskId}", user.getId(), task.getId())
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/users/{id}/task-types/{typeId}", user.getId(), type.getId())
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isConflict());
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(tasks.findById(task.getId())).isPresent();
        assertThat(types.findById(type.getId())).isPresent();
        assertThat(tokens.findAll()).hasSize(1);
        assertThat(audit.findAll()).hasSize(3).allSatisfy(event ->
                assertThat(event.getOutcome()).isEqualTo("FAILURE"));
    }

    @Test
    void protectedUserAndResourcesCannotBeModified() throws Exception {
        Usuario user = user(true);
        TipoTarea type = types.save(TipoTarea.builder().nombre("Protected type")
                .color("#123456").usuario(user).build());
        Tarea task = tasks.save(Tarea.builder().titulo("Protected task")
                .fecha(LocalDate.now()).usuario(user).tipoTarea(type).build());
        String jwt = token(admin(EnumSet.allOf(AdminPermission.class)));

        mvc.perform(patch("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"changed\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/users/{id}/enabled", user.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/users/{id}/task-types", user.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"New type\",\"color\":\"#654321\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/users/{id}/task-types/{typeId}", user.getId(), type.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Changed type\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/users/{id}/tasks", user.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titulo\":\"New task\",\"fecha\":\"" + LocalDate.now().plusDays(1)
                        + "\",\"tipoTareaId\":" + type.getId() + "}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/users/{id}/tasks/{taskId}", user.getId(), task.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titulo\":\"Changed task\"}"))
                .andExpect(status().isConflict());

        assertThat(users.findById(user.getId()).orElseThrow().getUsername()).isEqualTo(user.getUsername());
        assertThat(users.findById(user.getId()).orElseThrow().getEnabled()).isTrue();
        assertThat(types.findById(type.getId()).orElseThrow().getNombre()).isEqualTo("Protected type");
        assertThat(tasks.findById(task.getId()).orElseThrow().getTitulo()).isEqualTo("Protected task");
        assertThat(types.findAll()).hasSize(1);
        assertThat(tasks.findAll()).hasSize(1);
        assertThat(audit.findAll()).hasSize(6).allSatisfy(event ->
                assertThat(event.getOutcome()).isEqualTo("FAILURE"));
    }

    @Test
    void devFixturePolicyAllowsAlexAndBlocksSamAndDisabled() throws Exception {
        Usuario alex = users.save(Usuario.builder().username("alex-demo").email("alex@example.invalid")
                .password("hash").devFixtureKey("donit-dev-seed:v1:alex-demo")
                .protectedFromAdminMutation(false).build());
        Usuario sam = users.save(Usuario.builder().username("sam-demo").email("sam@example.invalid")
                .password("hash").devFixtureKey("donit-dev-seed:v1:sam-demo")
                .protectedFromAdminMutation(true).build());
        Usuario disabled = users.save(Usuario.builder().username("disabled-demo")
                .email("disabled@example.invalid").password("hash").enabled(false)
                .devFixtureKey("donit-dev-seed:v1:disabled-demo")
                .protectedFromAdminMutation(true).build());
        String jwt = token(admin(EnumSet.allOf(AdminPermission.class)));

        mvc.perform(patch("/api/admin/users/{id}", alex.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alex-qa\"}"))
                .andExpect(status().isOk());
        for (Usuario protectedFixture : java.util.List.of(sam, disabled)) {
            mvc.perform(patch("/api/admin/users/{id}", protectedFixture.getId())
                    .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"changed\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/admin/users/{id}", protectedFixture.getId())
                    .header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isConflict());
        }

        assertThat(users.findById(alex.getId()).orElseThrow().getUsername()).isEqualTo("alex-qa");
        assertThat(users.findById(sam.getId()).orElseThrow().getUsername()).isEqualTo("sam-demo");
        assertThat(users.findById(disabled.getId()).orElseThrow().getEnabled()).isFalse();
        assertThat(audit.findAll()).hasSize(5);
        assertThat(audit.findAll().stream().filter(event -> "FAILURE".equals(event.getOutcome())))
                .hasSize(4);
    }

    @Test
    void authorizedDeleteRemovesUserTasksTypesAndSessionsWithAudit() throws Exception {
        Usuario user = user(false);
        TipoTarea type = types.save(TipoTarea.builder().nombre("Type").color("#123456").usuario(user).build());
        Tarea task = tasks.save(Tarea.builder().titulo("Task").fecha(LocalDate.now())
                .usuario(user).tipoTarea(type).build());
        tokens.save(com.tareas.app.model.RefreshToken.builder().usuario(user).tokenHash("hash")
                .createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        AdminUser deleter = admin(EnumSet.of(AdminPermission.USER_DELETE));
        mvc.perform(delete("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + token(deleter)))
                .andExpect(status().isNoContent());
        assertThat(users.findById(user.getId())).isEmpty();
        assertThat(tasks.findById(task.getId())).isEmpty();
        assertThat(types.findById(type.getId())).isEmpty();
        assertThat(tokens.findAll()).isEmpty();
        assertThat(audit.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getOperation()).isEqualTo("USER_DELETE");
            assertThat(event.getOutcome()).isEqualTo("SUCCESS");
            assertThat(event.getAdminUserId()).isEqualTo(deleter.getId());
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedAdminRequestDoesNotLogItsSecret(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"DO_NOT_LOG_THIS_MARKER\",invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer DO_NOT_LOG_THIS_MARKER"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"DO_NOT_LOG_THIS_MARKER\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(output.getAll()).doesNotContain("DO_NOT_LOG_THIS_MARKER");
    }
}
