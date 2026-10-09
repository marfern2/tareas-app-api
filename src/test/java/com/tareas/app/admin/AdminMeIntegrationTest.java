package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminMeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminUserRepository admins;
    @Autowired AdminJwtService adminJwt;
    @Autowired JwtService androidJwt;

    private AdminUser admin(EnumSet<AdminPermission> permissions) {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        return admins.save(AdminUser.builder().username("admin-" + marker)
                .email("admin-" + marker + "@example.invalid")
                .passwordHash("secret-hash-" + marker).enabled(true)
                .createdAt(LocalDateTime.now()).permissions(permissions).build());
    }

    @Test
    void returnsOnlyCurrentAdminCapabilitiesAndReflectsRevocation() throws Exception {
        AdminUser admin = admin(EnumSet.of(AdminPermission.ADMIN_READ, AdminPermission.DEMO_READ,
                AdminPermission.DEMO_WRITE, AdminPermission.DEMO_PUBLISH, AdminPermission.DEMO_RESTORE));
        String authorization = "Bearer " + adminJwt.generateToken(admin.getEmail());

        var first = mvc.perform(get("/api/admin/me").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.username").value(admin.getUsername()))
                .andExpect(jsonPath("$.permissions.length()").value(5))
                .andExpect(jsonPath("$.permissions[?(@ == 'DEMO_READ')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'DEMO_WRITE')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'DEMO_PUBLISH')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'DEMO_RESTORE')]").exists())
                .andReturn();
        var json = mapper.readTree(first.getResponse().getContentAsString());
        assertThat(json.size()).isEqualTo(2);
        assertThat(first.getResponse().getContentAsString())
                .doesNotContain(admin.getEmail(), admin.getPasswordHash(), "refreshToken", "createdAt", "lastLogin");

        AdminUser current = admins.findById(admin.getId()).orElseThrow();
        current.getPermissions().removeAll(EnumSet.of(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE,
                AdminPermission.DEMO_PUBLISH, AdminPermission.DEMO_RESTORE));
        admins.saveAndFlush(current);
        mvc.perform(get("/api/admin/me").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("ADMIN_READ"));
    }

    @Test
    void requiresAdminJwtButNoDemoPermission() throws Exception {
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me").header("Authorization",
                "Bearer " + androidJwt.generateToken("android@example.invalid")))
                .andExpect(status().isUnauthorized());
        AdminUser admin = admin(EnumSet.noneOf(AdminPermission.class));
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + adminJwt.generateToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0));
    }
}
