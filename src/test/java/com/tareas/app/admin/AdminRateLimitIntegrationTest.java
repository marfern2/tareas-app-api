package com.tareas.app.admin;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.admin.security.AdminRateLimitFilter;
import com.tareas.app.security.RateLimitFilter;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.rate-limit.enabled=true",
        "app.admin.rate-limit.window-ms=5000",
        "app.admin.rate-limit.login-account-per-minute=2",
        "app.admin.rate-limit.read-per-minute=2",
        "app.admin.rate-limit.refresh-token-per-minute=1",
        "app.admin.rate-limit.refresh-account-per-minute=2",
        "app.admin.rate-limit.write-per-minute=1",
        "app.admin.rate-limit.delete-per-minute=1"
})
class AdminRateLimitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired UsuarioRepository users;
    @Autowired FilterChainProxy filterChainProxy;

    @Test
    void securityChainsInstallExactlyOneRateFilterEach() {
        var adminFilters = filterChainProxy.getFilters("/api/admin/users");
        var androidFilters = filterChainProxy.getFilters("/auth/login");
        org.assertj.core.api.Assertions.assertThat(adminFilters.stream().filter(AdminRateLimitFilter.class::isInstance).count())
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(adminFilters.stream().filter(RateLimitFilter.class::isInstance).count())
                .isZero();
        org.assertj.core.api.Assertions.assertThat(androidFilters.stream().filter(AdminRateLimitFilter.class::isInstance).count())
                .isZero();
        org.assertj.core.api.Assertions.assertThat(androidFilters.stream().filter(RateLimitFilter.class::isInstance).count())
                .isEqualTo(1);
    }

    @Test
    void loginReadAndRecoveryReturn429WithoutTouchingAndroidChain() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "rate-" + marker + "@example.invalid";
        admins.save(AdminUser.builder().username("rate-" + marker).email(email)
                .passwordHash(passwords.encode("test-only-password"))
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.ADMIN_READ)).build());
        String body = mapper.writeValueAsString(java.util.Map.of("email", email, "password", "test-only-password"));
        String token = mapper.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        Thread.sleep(5500);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.invalid\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWriteAndDeleteHaveSeparateLimits() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "rate-all-" + marker + "@example.invalid";
        admins.save(AdminUser.builder().username("rate-all-" + marker).email(email)
                .passwordHash(passwords.encode("test-only-password"))
                .enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.allOf(AdminPermission.class)).build());
        String body = mapper.writeValueAsString(java.util.Map.of("email", email, "password", "test-only-password"));
        var login = mapper.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String jwt = login.get("token").asText();
        String refresh = mapper.writeValueAsString(java.util.Map.of("refreshToken", login.get("refreshToken").asText()));
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refresh))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refresh))
                .andExpect(status().isTooManyRequests());

        Usuario first = users.save(Usuario.builder().username("rate-user-a-" + marker)
                .email("rate-user-a-" + marker + "@example.invalid").password("hash").build());
        Usuario second = users.save(Usuario.builder().username("rate-user-b-" + marker)
                .email("rate-user-b-" + marker + "@example.invalid").password("hash").build());
        mvc.perform(patch("/api/admin/users/{id}", first.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rate-updated-" + marker + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/admin/users/{id}", first.getId())
                .header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rate-again-" + marker + "\"}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(delete("/api/admin/users/{id}", first.getId())
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/users/{id}", second.getId())
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isTooManyRequests());
        Thread.sleep(5500);
        mvc.perform(delete("/api/admin/users/{id}", second.getId())
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isNoContent());
    }

    @Test
    void rotatedRefreshTokensStillShareAnAccountQuota() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "rotate-" + marker + "@example.invalid";
        admins.save(AdminUser.builder().username("rotate-" + marker).email(email)
                .passwordHash(passwords.encode("test-only-password"))
                .enabled(true).createdAt(LocalDateTime.now()).build());
        String loginBody = mapper.writeValueAsString(java.util.Map.of(
                "email", email, "password", "test-only-password"));
        var login = mapper.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String refresh = login.get("refreshToken").asText();
        for (int i = 0; i < 2; i++) {
            String body = mapper.writeValueAsString(java.util.Map.of("refreshToken", refresh));
            var result = mapper.readTree(mvc.perform(post("/api/admin/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            refresh = result.get("refreshToken").asText();
        }
        String third = mapper.writeValueAsString(java.util.Map.of("refreshToken", refresh));
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(third))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        Thread.sleep(5500);
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(third))
                .andExpect(status().isOk());
    }

    @Test
    void invalidRefreshTokenIsLimitedAndOversizedLoginEmailIsRejected() throws Exception {
        String invalid = mapper.writeValueAsString(java.util.Map.of("refreshToken", "invalid-only-for-test"));
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isTooManyRequests());
        String oversizedEmail = "a".repeat(250) + "@example.invalid";
        String login = mapper.writeValueAsString(java.util.Map.of("email", oversizedEmail, "password", "bad"));
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isBadRequest());
    }
}
