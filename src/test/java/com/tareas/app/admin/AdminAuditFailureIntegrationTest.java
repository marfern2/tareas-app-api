package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminAuditEvent;
import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminAuditEventRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditFailureIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminUserRepository admins;
    @Autowired UsuarioRepository users;
    @Autowired PasswordEncoder passwords;
    @MockitoBean AdminAuditEventRepository audit;

    @Test
    void destructiveOperationRollsBackWhenAuditCannotBeStored() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "audit-" + marker + "@example.invalid";
        AdminUser admin = admins.save(AdminUser.builder().username("audit-" + marker).email(email)
                .passwordHash(passwords.encode("test-only-password"))
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.USER_DELETE)).build());
        Usuario user = users.save(Usuario.builder().username("user-" + marker)
                .email("user-" + marker + "@example.invalid").password("hash").build());
        String body = mapper.writeValueAsString(java.util.Map.of("email", email, "password", "test-only-password"));
        String token = mapper.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();

        when(audit.saveAndFlush(any(AdminAuditEvent.class))).thenThrow(new IllegalStateException("audit unavailable"));
        mvc.perform(delete("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + token)).andExpect(status().isInternalServerError());
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(admins.findById(admin.getId())).isPresent();
    }

    @Test
    void userUpdateRollsBackWhenAuditCannotBeStored() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "audit-write-" + marker + "@example.invalid";
        admins.save(AdminUser.builder().username("audit-write-" + marker).email(email)
                .passwordHash(passwords.encode("test-only-password"))
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.USER_WRITE)).build());
        Usuario user = users.save(Usuario.builder().username("before-" + marker)
                .email("before-" + marker + "@example.invalid").password("hash").build());
        String loginBody = mapper.writeValueAsString(java.util.Map.of(
                "email", email, "password", "test-only-password"));
        String token = mapper.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        when(audit.saveAndFlush(any(AdminAuditEvent.class))).thenThrow(new IllegalStateException("audit unavailable"));
        mvc.perform(patch("/api/admin/users/{id}", user.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"after-" + marker + "\"}"))
                .andExpect(status().isInternalServerError());
        assertThat(users.findById(user.getId()).orElseThrow().getUsername()).isEqualTo("before-" + marker);
    }
}
