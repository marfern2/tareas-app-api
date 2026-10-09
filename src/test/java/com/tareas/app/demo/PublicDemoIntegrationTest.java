package com.tareas.app.demo;

import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.demo.model.*;
import com.tareas.app.demo.repository.*;
import com.tareas.app.security.JwtService;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.tareas.app.demo.PublicDemoIntegrationTest$SqlCapture")
@AutoConfigureMockMvc
class PublicDemoIntegrationTest {
    public static class SqlCapture implements StatementInspector {
        static final CopyOnWriteArrayList<String> statements = new CopyOnWriteArrayList<>();
        @Override public String inspect(String sql) {
            statements.add(sql.toLowerCase());
            return sql;
        }
    }
    private static final String BASE = "/api/public/demo";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DemoUserRepository users;
    @Autowired DemoTaskTypeRepository types;
    @Autowired DemoTaskRepository tasks;
    @Autowired AdminJwtService adminJwt;
    @Autowired JwtService androidJwt;

    @BeforeEach
    void clean() {
        tasks.deleteAll(); types.deleteAll(); users.deleteAll();
    }

    private DemoUser user(String handle, PublicationStatus status) {
        DemoUser u = new DemoUser();
        u.setHandle(handle); u.setDisplayName("Nombre " + handle); u.setBio("Bio " + handle);
        u.setFixtureKey("private-" + handle); u.setPublicationStatus(status);
        return users.saveAndFlush(u);
    }

    private DemoTaskType type(DemoUser u, String name, PublicationStatus status) {
        DemoTaskType t = new DemoTaskType();
        t.setDemoUserId(u.getId()); t.setName(name); t.setColor("#ABCDEF");
        t.setFixtureKey("private-" + UUID.randomUUID()); t.setPublicationStatus(status);
        return types.saveAndFlush(t);
    }

    private DemoTask task(DemoUser u, DemoTaskType t, String title, boolean completed, PublicationStatus status) {
        DemoTask k = new DemoTask();
        k.setDemoUserId(u.getId()); k.setDemoTaskTypeId(t.getId()); k.setTitle(title);
        k.setDueDate(LocalDate.of(2027, 1, 1)); k.setCompleted(completed); k.setUrgency(2);
        k.setFixtureKey("private-" + UUID.randomUUID()); k.setPublicationStatus(status);
        return tasks.saveAndFlush(k);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void visibilityPublicIdsDtoAndStats() throws Exception {
        DemoUser visible = user("visible", PublicationStatus.PUBLISHED);
        DemoUser hidden = user("hidden", PublicationStatus.DRAFT);
        DemoTaskType shownType = type(visible, "Work", PublicationStatus.PUBLISHED);
        DemoTaskType draftType = type(visible, "DraftType", PublicationStatus.DRAFT);
        DemoTaskType orphanType = type(hidden, "HiddenParent", PublicationStatus.PUBLISHED);
        DemoTask shown = task(visible, shownType, "Shown", true, PublicationStatus.PUBLISHED);
        task(visible, shownType, "DraftTask", false, PublicationStatus.DRAFT);
        DemoTask hiddenByType = task(visible, draftType, "HiddenByType", true, PublicationStatus.PUBLISHED);
        DemoTask hiddenByUser = task(hidden, orphanType, "HiddenByUser", true, PublicationStatus.PUBLISHED);

        JsonNode ul = json(mvc.perform(get(BASE + "/users")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn());
        assertThat(ul.get("totalElements").asLong()).isEqualTo(1);
        assertThat(ul.get("content").get(0).get("publicId").asText()).isEqualTo(visible.getPublicId().toString());
        JsonNode tl = json(mvc.perform(get(BASE + "/task-types")).andExpect(status().isOk()).andReturn());
        assertThat(tl.get("totalElements").asLong()).isEqualTo(1);
        JsonNode kl = json(mvc.perform(get(BASE + "/tasks")).andExpect(status().isOk()).andReturn());
        assertThat(kl.get("totalElements").asLong()).isEqualTo(1);
        assertThat(kl.get("content").get(0).get("publicId").asText()).isEqualTo(shown.getPublicId().toString());
        assertThat(kl.get("content").get(0).get("userPublicId").asText()).isEqualTo(visible.getPublicId().toString());
        assertThat(kl.get("content").get(0).get("taskTypePublicId").asText()).isEqualTo(shownType.getPublicId().toString());
        assertThat(kl.get("content").get(0).size()).isEqualTo(8);
        String serialized = kl.toString() + tl + ul;
        assertThat(serialized).doesNotContain("fixtureKey", "fixture_key", "version", "publicationStatus",
                "publishedAt", "createdAt", "updatedAt", "demoUserId", "demoTaskTypeId", "\"id\"");
        assertThat(json(mvc.perform(get(BASE + "/stats")).andExpect(status().isOk()).andReturn()).toString())
                .contains("\"users\":1", "\"taskTypes\":1", "\"tasks\":1", "\"completedTasks\":1")
                .doesNotContain("Draft", "total");
        mvc.perform(get(BASE + "/users/" + visible.getPublicId())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/task-types/" + shownType.getPublicId())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/tasks/" + shown.getPublicId())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/users/" + hidden.getPublicId())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/tasks/" + hiddenByType.getPublicId())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/tasks/" + hiddenByUser.getPublicId())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/users/" + visible.getId())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/tasks/" + shown.getId())).andExpect(status().isBadRequest());
    }

    @Test
    void onlyGetAndCredentialsDoNotChangePublicResult() throws Exception {
        user("visible", PublicationStatus.PUBLISHED);
        String baseline = mvc.perform(get(BASE + "/users")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        for (String token : new String[] {adminJwt.generateToken("admin@test.local"),
                androidJwt.generateToken("android@test.local")}) {
            assertThat(mvc.perform(get(BASE + "/users").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(baseline);
            mvc.perform(post(BASE + "/users").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE + "/tasks")).andExpect(status().isForbidden());
        mvc.perform(patch(BASE + "/tasks/" + UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/users/" + UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/users")).andExpect(status().isForbidden());
        mvc.perform(options(BASE + "/users")).andExpect(status().isForbidden());
    }

    @Test
    void paginationSearchSortingAndFilters() throws Exception {
        DemoUser one = user("alpha", PublicationStatus.PUBLISHED);
        DemoUser two = user("bravo", PublicationStatus.PUBLISHED);
        DemoTaskType oneType = type(one, "Work", PublicationStatus.PUBLISHED);
        DemoTaskType twoType = type(two, "Work", PublicationStatus.PUBLISHED);
        task(one, oneType, "Same", true, PublicationStatus.PUBLISHED);
        task(one, oneType, "Same", false, PublicationStatus.PUBLISHED);
        task(two, twoType, "Different", true, PublicationStatus.PUBLISHED);
        JsonNode defaultPage = json(mvc.perform(get(BASE + "/users")).andExpect(status().isOk()).andReturn());
        assertThat(defaultPage.get("page").asInt()).isZero();
        assertThat(defaultPage.get("size").asInt()).isEqualTo(20);
        assertThat(json(mvc.perform(get(BASE + "/users").param("search", "  ALPH  "))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong()).isEqualTo(1);
        assertThat(json(mvc.perform(get(BASE + "/task-types").param("userPublicId", one.getPublicId().toString())
                .param("search", "work")).andExpect(status().isOk()).andReturn()).get("totalElements").asLong()).isEqualTo(1);
        assertThat(json(mvc.perform(get(BASE + "/tasks").param("userPublicId", one.getPublicId().toString())
                .param("taskTypePublicId", oneType.getPublicId().toString())
                .param("completed", "true").param("urgency", "2").param("search", "same"))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong()).isEqualTo(1);
        JsonNode first = json(mvc.perform(get(BASE + "/tasks").param("sort", "title,desc")
                .param("size", "1")).andExpect(status().isOk()).andReturn());
        JsonNode second = json(mvc.perform(get(BASE + "/tasks").param("sort", "title,desc")
                .param("size", "1").param("page", "1")).andExpect(status().isOk()).andReturn());
        assertThat(first.get("content").get(0).get("publicId").asText())
                .isNotEqualTo(second.get("content").get(0).get("publicId").asText());
        assertThat(json(mvc.perform(get(BASE + "/tasks").param("sort", "title,desc")
                .param("size", "1")).andExpect(status().isOk()).andReturn()))
                .isEqualTo(first);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(json(mvc.perform(get(BASE + "/tasks").param("search", "%_"))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong()).isZero();
        for (String pair : new String[] {"size=21", "size=0", "page=50", "page=-1", "sort=id,asc",
                "search=x", "search=" + "x".repeat(61), "publicationStatus=DRAFT", "demoUserId=1"}) {
            String[] parts = pair.split("=", 2);
            mvc.perform(get(BASE + "/tasks").param(parts[0], parts[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "/tasks").param("page", "49").param("size", "20"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/users").param("sort", "handle,desc")).andExpect(status().isOk());
        mvc.perform(get(BASE + "/task-types").param("sort", "name,asc")).andExpect(status().isOk());
        mvc.perform(get(BASE + "/tasks").param("completed", "maybe")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/tasks").param("urgency", "3")).andExpect(status().isBadRequest());
    }

    @Test
    void publicReadsIssueOnlyDemoTableQueries() throws Exception {
        DemoUser u = user("visible", PublicationStatus.PUBLISHED);
        DemoTaskType t = type(u, "Work", PublicationStatus.PUBLISHED);
        DemoTask k = task(u, t, "Visible task", true, PublicationStatus.PUBLISHED);
        SqlCapture.statements.clear();
        for (String path : new String[] {"/users", "/users/" + u.getPublicId(),
                "/task-types", "/task-types/" + t.getPublicId(),
                "/tasks", "/tasks/" + k.getPublicId(), "/stats"}) {
            mvc.perform(get(BASE + path).header("Authorization", "Bearer ignored"))
                    .andExpect(status().isOk());
        }
        assertThat(SqlCapture.statements).isNotEmpty();
        assertThat(SqlCapture.statements).allSatisfy(sql -> {
            assertThat(sql).doesNotContain(" admin_", " usuarios ", " tareas ", " tipos_tarea ");
            assertThat(sql).contains("demo_");
        });
    }
}
