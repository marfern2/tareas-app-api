package com.tareas.app.demo;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.cors.allowed-origins=https://admin-dev.marfern.dev")
class DemoStrongEtagHttpTest {
    private static final String BASE = "/api/admin/demo";
    private static final String ORIGIN = "https://admin-dev.marfern.dev";

    @LocalServerPort int port;
    @Autowired AdminUserRepository admins;
    @Autowired AdminJwtService jwt;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    private String token() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "etag-" + suffix + "@example.invalid";
        admins.save(AdminUser.builder().username("etag-" + suffix).email(email)
                .passwordHash("unused").enabled(true).createdAt(LocalDateTime.now())
                .permissions(EnumSet.of(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE,
                        AdminPermission.DEMO_PUBLISH)).build());
        return "Bearer " + jwt.generateToken(email);
    }

    private HttpResponse<String> request(String method, String path, String auth, String ifMatch, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", auth).header("Origin", ORIGIN)
                .header("Accept-Encoding", "gzip, br")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long id(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body()).get("id").asLong();
    }

    private String strongTag(HttpResponse<String> response, String expected) {
        String tag = response.headers().firstValue("ETag").orElseThrow();
        assertThat(tag).isEqualTo(expected).doesNotStartWith("W/");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store", "no-transform");
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
                .containsIgnoringCase("ETag").containsIgnoringCase("Retry-After");
        return tag;
    }

    @Test
    void userDetailPatchAndPublicationRoundTripLiteralStrongEtags() throws Exception {
        String auth = token();
        String handle = "etag-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = request("POST", BASE + "/users", auth, null,
                "{\"handle\":\"" + handle + "\",\"displayName\":\"Before\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        strongTag(created, "\"v0\"");
        String path = BASE + "/users/" + id(created);
        HttpResponse<String> detail = request("GET", path, auth, null, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        String original = strongTag(detail, "\"v0\"");

        assertThat(request("PATCH", path, auth, null, "{\"displayName\":\"After\"}").statusCode())
                .isEqualTo(428);
        HttpResponse<String> patched = request("PATCH", path, auth, original,
                "{\"displayName\":\"After\"}");
        assertThat(patched.statusCode()).isEqualTo(200);
        String current = strongTag(patched, "\"v1\"");
        assertThat(request("PATCH", path, auth, original, "{\"displayName\":\"Stale\"}").statusCode())
                .isEqualTo(412);
        assertThat(request("PATCH", path, auth, "W/" + current,
                "{\"displayName\":\"Weak\"}").statusCode()).isEqualTo(400);

        String publicationPath = path + "/publication";
        assertThat(request("PATCH", publicationPath, auth, null,
                "{\"publicationStatus\":\"PUBLISHED\"}").statusCode()).isEqualTo(428);
        HttpResponse<String> published = request("PATCH", publicationPath, auth, current,
                "{\"publicationStatus\":\"PUBLISHED\"}");
        assertThat(published.statusCode()).isEqualTo(200);
        strongTag(published, "\"v2\"");
        assertThat(request("PATCH", publicationPath, auth, current,
                "{\"publicationStatus\":\"PUBLISHED\"}").statusCode()).isEqualTo(412);
    }

    @Test
    void typesAndTasksUseStrongEtagsForPatchPublicationAndDelete() throws Exception {
        String auth = token();
        String handle = "etag-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> user = request("POST", BASE + "/users", auth, null,
                "{\"handle\":\"" + handle + "\",\"displayName\":\"Owner\"}");
        long userId = id(user);
        assertThat(request("PATCH", BASE + "/users/" + userId + "/publication", auth,
                strongTag(user, "\"v0\""), "{\"publicationStatus\":\"PUBLISHED\"}").statusCode())
                .isEqualTo(200);
        HttpResponse<String> type = request("POST", BASE + "/task-types", auth, null,
                "{\"demoUserId\":" + userId + ",\"name\":\"Work\",\"color\":\"#aabbcc\"}");
        assertThat(type.statusCode()).isEqualTo(201);
        strongTag(type, "\"v0\"");
        String typePath = BASE + "/task-types/" + id(type);
        String typeTag = strongTag(request("GET", typePath, auth, null, null), "\"v0\"");
        HttpResponse<String> changedType = request("PATCH", typePath, auth, typeTag, "{\"name\":\"Home\"}");
        assertThat(changedType.statusCode()).isEqualTo(200);
        String changedTypeTag = strongTag(changedType, "\"v1\"");
        assertThat(request("DELETE", typePath, auth, typeTag, null).statusCode()).isEqualTo(412);
        assertThat(request("PATCH", typePath + "/publication", auth, null,
                "{\"publicationStatus\":\"PUBLISHED\"}").statusCode()).isEqualTo(428);
        HttpResponse<String> publishedType = request("PATCH", typePath + "/publication", auth,
                changedTypeTag, "{\"publicationStatus\":\"PUBLISHED\"}");
        assertThat(publishedType.statusCode()).isEqualTo(200);
        String publishedTypeTag = strongTag(publishedType, "\"v2\"");
        assertThat(request("PATCH", typePath + "/publication", auth, changedTypeTag,
                "{\"publicationStatus\":\"PUBLISHED\"}").statusCode()).isEqualTo(412);

        HttpResponse<String> task = request("POST", BASE + "/tasks", auth, null,
                "{\"demoUserId\":" + userId + ",\"demoTaskTypeId\":" + id(type)
                        + ",\"title\":\"Demo task\",\"dueDate\":\"2027-01-01\",\"completed\":false,\"urgency\":1}");
        assertThat(task.statusCode()).isEqualTo(201);
        strongTag(task, "\"v0\"");
        String taskPath = BASE + "/tasks/" + id(task);
        String taskTag = strongTag(request("GET", taskPath, auth, null, null), "\"v0\"");
        HttpResponse<String> changedTask = request("PATCH", taskPath, auth, taskTag,
                "{\"completed\":true}");
        assertThat(changedTask.statusCode()).isEqualTo(200);
        String changedTaskTag = strongTag(changedTask, "\"v1\"");
        HttpResponse<String> publishedTask = request("PATCH", taskPath + "/publication", auth,
                changedTaskTag, "{\"publicationStatus\":\"PUBLISHED\"}");
        assertThat(publishedTask.statusCode()).isEqualTo(200);
        String publishedTaskTag = strongTag(publishedTask, "\"v2\"");
        assertThat(request("PATCH", taskPath + "/publication", auth, changedTaskTag,
                "{\"publicationStatus\":\"PUBLISHED\"}").statusCode()).isEqualTo(412);
        assertThat(request("DELETE", taskPath, auth, null, null).statusCode()).isEqualTo(428);
        assertThat(request("DELETE", taskPath, auth, taskTag, null).statusCode()).isEqualTo(412);
        assertThat(request("DELETE", taskPath, auth, publishedTaskTag, null).statusCode()).isEqualTo(204);
        assertThat(request("DELETE", typePath, auth, null, null).statusCode()).isEqualTo(428);
        assertThat(request("DELETE", typePath, auth, publishedTypeTag, null).statusCode()).isEqualTo(204);
    }
}
