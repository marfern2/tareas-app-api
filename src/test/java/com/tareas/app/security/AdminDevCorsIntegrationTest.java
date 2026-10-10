package com.tareas.app.security;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.cors.allowed-origins=https://admin-dev.marfern.dev",
        "app.admin.rate-limit.enabled=true",
        "app.admin.rate-limit.read-per-minute=1"
})
class AdminDevCorsIntegrationTest {
    private static final String ORIGIN = "https://admin-dev.marfern.dev";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminUserRepository admins;
    @Autowired AdminJwtService jwt;

    private String token() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "cors-" + marker + "@example.invalid";
        admins.save(AdminUser.builder().username("cors-" + marker).email(email)
                .passwordHash("unused").enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE)).build());
        return "Bearer " + jwt.generateToken(email);
    }

    @Test
    void devPreflightAllowsIfMatchForPatchDeletePublicationAndRestore() throws Exception {
        for (String[] request : new String[][] {
                {"PATCH", "/api/admin/demo/users/1"},
                {"DELETE", "/api/admin/demo/tasks/1"},
                {"PATCH", "/api/admin/demo/users/1/publication"},
                {"POST", "/api/admin/demo/fixtures/restore"}}) {
            var result = mvc.perform(options(request[1]).header("Origin", ORIGIN)
                    .header("Access-Control-Request-Method", request[0])
                    .header("Access-Control-Request-Headers", "authorization,content-type,if-match"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andReturn().getResponse();
            assertThat(result.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("if-match");
            assertThat(result.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
        }
    }

    @Test
    void devEtagsAndRetryAfterAreReadableFromBrowser() throws Exception {
        String auth = token();
        var created = mvc.perform(post("/api/admin/demo/users").header("Origin", ORIGIN)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"cors-test-" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"displayName\":\"CORS test\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"))
                .andReturn().getResponse();
        long id = mapper.readTree(created.getContentAsString()).get("id").asLong();
        var detail = mvc.perform(get("/api/admin/demo/users/{id}", id)
                .header("Origin", ORIGIN).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn().getResponse();
        assertThat(detail.getHeader("Access-Control-Expose-Headers"))
                .containsIgnoringCase("ETag").containsIgnoringCase("Retry-After");
        assertThat(detail.getHeader("ETag")).isEqualTo(created.getHeader("ETag"));

        var limited = mvc.perform(get("/api/admin/demo/users/{id}", id)
                .header("Origin", ORIGIN).header("Authorization", auth))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse();
        assertThat(limited.getHeader("Access-Control-Expose-Headers"))
                .containsIgnoringCase("Retry-After");
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
    }

    @Test
    void untrustedOriginAndWildcardRemainRejected() throws Exception {
        var response = mvc.perform(options("/api/admin/demo/users/1")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type,if-match"))
                .andReturn().getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isNull();
    }
}
