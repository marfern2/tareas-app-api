package com.tareas.app.demo;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminAuditEventRepository;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminJwtService;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.demo.repository.*;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import com.tareas.app.repository.UsuarioRepository;
import com.tareas.app.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DemoAdminIntegrationTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String BASE = "/api/admin/demo";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminUserRepository admins;
    @Autowired AdminAuditEventRepository audits;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminJwtService adminJwt;
    @Autowired JwtService androidJwt;
    @Autowired DemoUserRepository users;
    @Autowired DemoTaskTypeRepository types;
    @Autowired DemoTaskRepository tasks;
    @Autowired UsuarioRepository realUsers;
    @Autowired TipoTareaRepository realTypes;
    @Autowired TareaRepository realTasks;

    @BeforeEach
    void cleanDemo() {
        tasks.deleteAll(); types.deleteAll(); users.deleteAll();
        audits.deleteAll();
    }

    private String token(AdminPermission... permissions) {
        int n = SEQ.incrementAndGet();
        String email = "demo-admin-" + n + "@test.local";
        admins.save(AdminUser.builder().username("demo-admin-" + n).email(email)
                .passwordHash("unused").enabled(true).createdAt(LocalDateTime.now())
                .permissions(permissions.length == 0 ? EnumSet.noneOf(AdminPermission.class) : EnumSet.of(permissions[0], permissions))
                .build());
        return "Bearer " + adminJwt.generateToken(email);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult user(String auth, String handle) throws Exception {
        return mvc.perform(post(BASE + "/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"" + handle + "\",\"displayName\":\"Demo User\",\"bio\":\"Safe bio\"}"))
                .andExpect(status().isCreated()).andReturn();
    }
    private MvcResult type(String auth, long userId) throws Exception {
        return mvc.perform(post(BASE + "/task-types").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + userId + ",\"name\":\"Work\",\"color\":\"#aabbcc\"}"))
                .andExpect(status().isCreated()).andReturn();
    }
    private MvcResult task(String auth, long userId, long typeId) throws Exception {
        return mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + userId + ",\"demoTaskTypeId\":" + typeId
                        + ",\"title\":\"Demo task\",\"dueDate\":\"2027-01-01\",\"completed\":false,\"urgency\":1}"))
                .andExpect(status().isCreated()).andReturn();
    }
    private MvcResult publish(String auth, String resource, long id, String etag, String state) throws Exception {
        return mvc.perform(patch(BASE + "/" + resource + "/" + id + "/publication")
                .header("Authorization", auth).header("If-Match", etag).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationStatus\":\"" + state + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    @Test
    void authorizationAndAndroidJwt() throws Exception {
        mvc.perform(get(BASE + "/users")).andExpect(status().isUnauthorized());
        String real = token(AdminPermission.ADMIN_READ, AdminPermission.USER_WRITE,
                AdminPermission.USER_DELETE, AdminPermission.TASK_WRITE);
        mvc.perform(get(BASE + "/users").header("Authorization", real)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/users").header("Authorization", real).contentType(MediaType.APPLICATION_JSON)
                .content("{}" )).andExpect(status().isForbidden());
        String read = token(AdminPermission.DEMO_READ);
        mvc.perform(get(BASE + "/users").header("Authorization", read)).andExpect(status().isOk());
        mvc.perform(post(BASE + "/users").header("Authorization", read).contentType(MediaType.APPLICATION_JSON)
                .content("{}" )).andExpect(status().isForbidden());
        String write = token(AdminPermission.DEMO_WRITE);
        long id = json(user(write, "writer")).get("id").asLong();
        mvc.perform(get(BASE + "/users").header("Authorization", write)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/users/" + id).header("Authorization", write)).andExpect(status().isForbidden());
        mvc.perform(patch(BASE + "/users/" + id + "/publication").header("Authorization", write)
                .header("If-Match", "\"v0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationStatus\":\"PUBLISHED\"}" )).andExpect(status().isForbidden());
        String publisher = token(AdminPermission.DEMO_PUBLISH);
        publish(publisher, "users", id, "\"v0\"", "PUBLISHED");
        mvc.perform(get(BASE + "/users").header("Authorization", publisher)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/users/" + id).header("Authorization", publisher)).andExpect(status().isForbidden());
        mvc.perform(patch(BASE + "/users/" + id).header("Authorization", publisher)
                .header("If-Match", "\"v1\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Other\"}" )).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/users").header("Authorization", "Bearer " + androidJwt.generateToken("someone")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crudFiltersStatsAndIsolation() throws Exception {
        long realU = realUsers.count(), realT = realTypes.count(), realK = realTasks.count();
        String auth = token(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE, AdminPermission.DEMO_PUBLISH);
        MvcResult u = user(auth, "  ALICE  ");
        long uid = json(u).get("id").asLong();
        assertThat(json(u).get("handle").asText()).isEqualTo("alice");
        MvcResult ty = type(auth, uid); long tid = json(ty).get("id").asLong();
        MvcResult ta = task(auth, uid, tid); long kid = json(ta).get("id").asLong();
        mvc.perform(get(BASE + "/users/" + uid).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/task-types/" + tid).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/tasks/" + kid).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/users?search=ali&publicationStatus=DRAFT&size=1&sort=handle,desc")
                .header("Authorization", auth)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BASE + "/task-types?demoUserId=" + uid + "&search=wor")
                .header("Authorization", auth)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BASE + "/tasks?demoUserId=" + uid + "&demoTaskTypeId=" + tid + "&completed=false&urgency=1&search=task")
                .header("Authorization", auth)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BASE + "/stats").header("Authorization", auth))
                .andExpect(jsonPath("$.usersTotal").value(1)).andExpect(jsonPath("$.typesTotal").value(1))
                .andExpect(jsonPath("$.tasksTotal").value(1));
        mvc.perform(patch(BASE + "/users/" + uid).header("Authorization", auth).header("If-Match", u.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Alice Demo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(BASE + "/task-types/" + tid).header("Authorization", auth).header("If-Match", ty.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Home\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        MvcResult changed = mvc.perform(patch(BASE + "/tasks/" + kid).header("Authorization", auth).header("If-Match", ta.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(delete(BASE + "/tasks/" + kid).header("Authorization", auth).header("If-Match", changed.getResponse().getHeader("ETag")))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/task-types/" + tid).header("Authorization", auth).header("If-Match", "\"v1\""))
                .andExpect(status().isNoContent());
        assertThat(realUsers.count()).isEqualTo(realU);
        assertThat(realTypes.count()).isEqualTo(realT);
        assertThat(realTasks.count()).isEqualTo(realK);
    }

    @Test
    void validationRelationshipsAndPaging() throws Exception {
        String auth = token(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE);
        long uid = json(user(auth, "alice")).get("id").asLong();
        long other = json(user(auth, "other")).get("id").asLong();
        long tid = json(type(auth, uid)).get("id").asLong();
        mvc.perform(post(BASE + "/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"ALICE\",\"displayName\":\"Other\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"ab\",\"displayName\":\"O\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/task-types").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + uid + ",\"name\":\"A\",\"color\":\"red\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + other + ",\"demoTaskTypeId\":" + tid
                        + ",\"title\":\"Valid task\",\"dueDate\":\"2027-01-01\",\"completed\":false,\"urgency\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + uid + ",\"demoTaskTypeId\":" + tid
                        + ",\"title\":\"<b>bad</b>\",\"dueDate\":\"2027-01-01\",\"completed\":false,\"urgency\":4}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/users?size=101").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/users?sort=passwordHash").header("Authorization", auth)).andExpect(status().isBadRequest());
    }

    @Test
    void publicationRulesAndBlockedUnpublication() throws Exception {
        String auth = token(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE, AdminPermission.DEMO_PUBLISH);
        MvcResult u = user(auth, "alice"); long uid = json(u).get("id").asLong();
        MvcResult other = user(auth, "other"); long oid = json(other).get("id").asLong();
        MvcResult ty = type(auth, uid); long tid = json(ty).get("id").asLong();
        MvcResult ta = task(auth, uid, tid); long kid = json(ta).get("id").asLong();
        mvc.perform(patch(BASE + "/task-types/" + tid + "/publication").header("Authorization", auth)
                .header("If-Match", ty.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationStatus\":\"PUBLISHED\"}" )).andExpect(status().isConflict());
        mvc.perform(patch(BASE + "/tasks/" + kid + "/publication").header("Authorization", auth)
                .header("If-Match", ta.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationStatus\":\"PUBLISHED\"}" )).andExpect(status().isConflict());
        MvcResult pu = publish(auth, "users", uid, u.getResponse().getHeader("ETag"), "PUBLISHED");
        MvcResult pt = publish(auth, "task-types", tid, ty.getResponse().getHeader("ETag"), "PUBLISHED");
        publish(auth, "tasks", kid, ta.getResponse().getHeader("ETag"), "PUBLISHED");
        mvc.perform(patch(BASE + "/users/" + uid + "/publication").header("Authorization", auth)
                .header("If-Match", pu.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationStatus\":\"DRAFT\"}" )).andExpect(status().isConflict());
        mvc.perform(patch(BASE + "/task-types/" + tid + "/publication").header("Authorization", auth)
                .header("If-Match", pt.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationStatus\":\"DRAFT\"}" )).andExpect(status().isConflict());
        mvc.perform(delete(BASE + "/task-types/" + tid).header("Authorization", auth)
                .header("If-Match", pt.getResponse().getHeader("ETag"))).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + oid + ",\"demoTaskTypeId\":" + tid
                        + ",\"title\":\"Wrong owner\",\"dueDate\":\"2027-01-01\",\"completed\":false,\"urgency\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void preconditionsAndAuditWithoutSecrets() throws Exception {
        String auth = token(AdminPermission.DEMO_READ, AdminPermission.DEMO_WRITE, AdminPermission.DEMO_PUBLISH);
        MvcResult u = user(auth, "audituser"); long uid = json(u).get("id").asLong();
        mvc.perform(patch(BASE + "/users/" + uid).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Changed\"}" )).andExpect(status().isPreconditionRequired());
        mvc.perform(patch(BASE + "/users/" + uid).header("Authorization", auth).header("If-Match", "\"v99\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Changed\"}" ))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch(BASE + "/users/" + uid).header("Authorization", auth).header("If-Match", "\"v0\"")
                .header("X-Request-ID", "demo-test-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"PRIVATE_SENTINEL\"}" )).andExpect(status().isOk());
        assertThat(audits.findAll()).anySatisfy(e -> {
            assertThat(e.getOperation()).isEqualTo("DEMO_USER_UPDATE");
            assertThat(e.getOutcome()).isEqualTo("SUCCESS");
            assertThat(e.getResourceId()).isEqualTo(uid);
            assertThat(e.getAdminUserId()).isNotNull();
            assertThat(e.getCorrelationId()).isEqualTo("demo-test-123");
        });
        assertThat(audits.findAll()).anySatisfy(e -> {
            assertThat(e.getOutcome()).isEqualTo("FAILURE");
            assertThat(e.getAdminUserId()).isNotNull();
        });
        assertThat(jdbc.queryForList("select * from admin_audit_events").toString())
                .doesNotContain("PRIVATE_SENTINEL").doesNotContain(auth);
    }

    @Test
    void fieldLimitsAndInvalidDates() throws Exception {
        String auth = token(AdminPermission.DEMO_WRITE);
        long uid = json(user(auth, "limits")).get("id").asLong();
        String longName = "A".repeat(81);
        mvc.perform(post(BASE + "/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"validhandle\",\"displayName\":\"" + longName + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"validhandle\",\"displayName\":\"Name\",\"bio\":\"https://example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"validhandle\",\"displayName\":\"Name\",\"bio\":\"example.com/path\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/task-types").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + uid + ",\"name\":\"" + "N".repeat(51) + "\",\"color\":\"#AABBCC\"}"))
                .andExpect(status().isBadRequest());
        long tid = json(type(auth, uid)).get("id").asLong();
        mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + uid + ",\"demoTaskTypeId\":" + tid
                        + ",\"title\":\"Valid title\",\"dueDate\":\"2027-02-30\",\"completed\":false,\"urgency\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + uid + ",\"demoTaskTypeId\":" + tid
                        + ",\"title\":\"Valid title\",\"dueDate\":\"2027-01-01\",\"urgency\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/tasks").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"demoUserId\":" + uid + ",\"demoTaskTypeId\":" + tid
                        + ",\"title\":\"" + "T".repeat(101)
                        + "\",\"dueDate\":\"2027-01-01\",\"completed\":false,\"urgency\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preconditionsCoverTypeAndTask() throws Exception {
        String auth = token(AdminPermission.DEMO_WRITE);
        long uid = json(user(auth, "etaguser")).get("id").asLong();
        MvcResult ty = type(auth, uid); long tid = json(ty).get("id").asLong();
        MvcResult ta = task(auth, uid, tid); long kid = json(ta).get("id").asLong();
        mvc.perform(delete(BASE + "/task-types/" + tid).header("Authorization", auth))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(patch(BASE + "/task-types/" + tid).header("Authorization", auth)
                .header("If-Match", "\"v3\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(delete(BASE + "/tasks/" + kid).header("Authorization", auth))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(patch(BASE + "/tasks/" + kid).header("Authorization", auth)
                .header("If-Match", "\"v3\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Other title\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(delete(BASE + "/tasks/" + kid).header("Authorization", auth)
                .header("If-Match", "\"v3\"")).andExpect(status().isPreconditionFailed());
        assertThat(ty.getResponse().getHeader("ETag")).isEqualTo("\"v0\"");
        assertThat(ta.getResponse().getHeader("ETag")).isEqualTo("\"v0\"");
    }
}
