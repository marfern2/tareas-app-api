package com.tareas.app.demo;

import com.tareas.app.demo.model.DemoTask;
import com.tareas.app.demo.model.DemoTaskType;
import com.tareas.app.demo.model.DemoUser;
import com.tareas.app.demo.model.PublicationStatus;
import com.tareas.app.demo.repository.DemoTaskRepository;
import com.tareas.app.demo.repository.DemoTaskTypeRepository;
import com.tareas.app.demo.repository.DemoUserRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.tareas.app.demo.PublicDemoPostgresqlTest$SqlCapture"
})
@AutoConfigureMockMvc
@Testcontainers
class PublicDemoPostgresqlTest {
    public static class SqlCapture implements StatementInspector {
        static final CopyOnWriteArrayList<String> statements = new CopyOnWriteArrayList<>();

        @Override public String inspect(String sql) {
            statements.add(sql.toLowerCase());
            return sql;
        }
    }

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String BASE = "/api/public/demo";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DemoUserRepository users;
    @Autowired DemoTaskTypeRepository types;
    @Autowired DemoTaskRepository tasks;

    private DemoUser alpha;
    private DemoTaskType alphaType;
    private DemoTask alphaTask;

    @BeforeEach
    void fixtures() {
        tasks.deleteAll();
        types.deleteAll();
        users.deleteAll();

        alpha = user("alpha", PublicationStatus.PUBLISHED);
        DemoUser bravo = user("bravo", PublicationStatus.PUBLISHED);
        DemoUser draftUser = user("draft-user", PublicationStatus.DRAFT);
        alphaType = type(alpha, "Alpha Work", PublicationStatus.PUBLISHED);
        DemoTaskType bravoType = type(bravo, "Bravo Work", PublicationStatus.PUBLISHED);
        DemoTaskType draftType = type(alpha, "Draft Type", PublicationStatus.DRAFT);
        DemoTaskType hiddenParentType = type(draftUser, "Hidden Parent Type", PublicationStatus.PUBLISHED);
        alphaTask = task(alpha, alphaType, "Alpha Task", true, PublicationStatus.PUBLISHED);
        task(bravo, bravoType, "Bravo Task", false, PublicationStatus.PUBLISHED);
        task(alpha, alphaType, "Draft Task", true, PublicationStatus.DRAFT);
        task(alpha, draftType, "Hidden By Type", true, PublicationStatus.PUBLISHED);
        task(draftUser, hiddenParentType, "Hidden By User", true, PublicationStatus.PUBLISHED);
    }

    @Test
    void usersWithAndWithoutSearchOnPostgresql() throws Exception {
        assertThat(request("/users").get("totalElements").asLong()).isEqualTo(2);
        JsonNode page = requestWithoutSearch("/users", "sort", "handle,desc", "size", "1");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.get("content").get(0).get("handle").asText()).isEqualTo("bravo");
        assertThat(request("/users", "sort", "handle,desc", "size", "1", "page", "1")
                .get("content").get(0).get("publicId").asText()).isEqualTo(alpha.getPublicId().toString());
        assertThat(request("/users", "search", "  ALPH  ").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void taskTypesWithAndWithoutSearchOnPostgresql() throws Exception {
        assertThat(request("/task-types").get("totalElements").asLong()).isEqualTo(2);
        JsonNode page = requestWithoutSearch("/task-types", "sort", "name,desc", "size", "1");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.get("content").get(0).get("name").asText()).isEqualTo("Bravo Work");
        JsonNode filtered = request("/task-types", "userPublicId", alpha.getPublicId().toString());
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.get("content").get(0).get("publicId").asText())
                .isEqualTo(alphaType.getPublicId().toString());
        assertThat(request("/task-types", "search", "  ALPHA  ").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void tasksWithAndWithoutSearchOnPostgresql() throws Exception {
        assertThat(request("/tasks").get("totalElements").asLong()).isEqualTo(2);
        JsonNode page = requestWithoutSearch("/tasks", "sort", "title,desc", "size", "1");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.get("content").get(0).get("title").asText()).isEqualTo("Bravo Task");
        assertThat(request("/tasks", "sort", "title,desc", "size", "1", "page", "1")
                .get("content").get(0).get("publicId").asText()).isEqualTo(alphaTask.getPublicId().toString());
        JsonNode filtered = request("/tasks", "userPublicId", alpha.getPublicId().toString(),
                "taskTypePublicId", alphaType.getPublicId().toString(), "completed", "true", "urgency", "2");
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.get("content").get(0).get("publicId").asText()).isEqualTo(alphaTask.getPublicId().toString());
        assertThat(request("/tasks", "search", "  ALPHA  ").get("totalElements").asLong()).isEqualTo(1);
    }

    private JsonNode requestWithoutSearch(String path, String... params) throws Exception {
        SqlCapture.statements.clear();
        JsonNode result = request(path, params);
        assertThat(SqlCapture.statements).isNotEmpty()
                .allSatisfy(sql -> assertThat(sql).doesNotContain(" like "));
        return result;
    }

    private JsonNode request(String path, String... params) throws Exception {
        var request = get(BASE + path);
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private DemoUser user(String handle, PublicationStatus status) {
        DemoUser entity = new DemoUser();
        entity.setHandle(handle);
        entity.setDisplayName("Nombre " + handle);
        entity.setFixtureKey("public-test-" + handle);
        entity.setPublicationStatus(status);
        if (status == PublicationStatus.PUBLISHED) entity.setPublishedAt(Instant.now());
        return users.saveAndFlush(entity);
    }

    private DemoTaskType type(DemoUser owner, String name, PublicationStatus status) {
        DemoTaskType entity = new DemoTaskType();
        entity.setDemoUserId(owner.getId());
        entity.setName(name);
        entity.setColor("#ABCDEF");
        entity.setFixtureKey("public-test-" + UUID.randomUUID());
        entity.setPublicationStatus(status);
        if (status == PublicationStatus.PUBLISHED) entity.setPublishedAt(Instant.now());
        return types.saveAndFlush(entity);
    }

    private DemoTask task(DemoUser owner, DemoTaskType type, String title, boolean completed,
                          PublicationStatus status) {
        DemoTask entity = new DemoTask();
        entity.setDemoUserId(owner.getId());
        entity.setDemoTaskTypeId(type.getId());
        entity.setTitle(title);
        entity.setDueDate(LocalDate.of(2027, 1, 1));
        entity.setCompleted(completed);
        entity.setUrgency(2);
        entity.setFixtureKey("public-test-" + UUID.randomUUID());
        entity.setPublicationStatus(status);
        if (status == PublicationStatus.PUBLISHED) entity.setPublishedAt(Instant.now());
        return tasks.saveAndFlush(entity);
    }
}
