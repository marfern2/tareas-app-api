package com.tareas.app.db;

import com.tareas.app.demo.model.*;
import com.tareas.app.demo.repository.*;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class DemoCatalogPostgresqlTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired EntityManagerFactory emf;
    @Autowired DemoUserRepository users;
    @Autowired DemoTaskTypeRepository types;
    @Autowired DemoTaskRepository tasks;
    @Autowired DemoCatalogControlRepository control;

    @Test
    void v8KeepsBothContextsStructurallySeparate() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(jdbc.queryForList("""
                SELECT tc.table_name || '->' || ccu.table_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name AND tc.constraint_schema = ccu.constraint_schema
                WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'
                  AND ((tc.table_name LIKE 'demo_%' AND ccu.table_name NOT LIKE 'demo_%')
                    OR (tc.table_name NOT LIKE 'demo_%' AND ccu.table_name LIKE 'demo_%'))
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='public' AND table_name IN ('demo_users','demo_task_types','demo_tasks','demo_catalog_control')
                """, String.class)).noneMatch(name -> name.matches("(?i).*(email|password|credential|secret|token).*|usuario_id"));
        for (EntityType<?> entity : emf.getMetamodel().getEntities()) {
            boolean demo = entity.getJavaType().getPackageName().startsWith("com.tareas.app.demo.");
            entity.getAttributes().forEach(attribute -> {
                Class<?> fieldType = attribute.getJavaType();
                String targetPackage = fieldType.getPackageName();
                if (demo) {
                    assertThat(targetPackage).as("%s.%s", entity.getName(), attribute.getName())
                            .doesNotStartWith("com.tareas.app.model")
                            .doesNotStartWith("com.tareas.app.admin.model");
                } else {
                    assertThat(targetPackage).as("%s.%s", entity.getName(), attribute.getName())
                            .doesNotStartWith("com.tareas.app.demo.model");
                }
            });
        }
    }

    @Test
    void constraintsRejectCrossOwnerTypeDuplicateKeysAndInvalidState() {
        long first = insertUser("alpha");
        long second = insertUser("bravo");
        long type = insertType(first);
        jdbc.update("INSERT INTO demo_tasks (demo_user_id, demo_task_type_id, title, due_date) VALUES (?, ?, 'valid', CURRENT_DATE)", first, type);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_tasks (demo_user_id, demo_task_type_id, title, due_date) VALUES (?, ?, 'cross', CURRENT_DATE)", second, type))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_tasks (demo_user_id, demo_task_type_id, title, due_date) VALUES (?, ?, 'missing', CURRENT_DATE)", first, 999999))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_tasks SET demo_user_id = ? WHERE demo_task_type_id = ?", second, type))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_task_types SET demo_user_id = ? WHERE id = ?", second, type))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID publicId = jdbc.queryForObject("SELECT public_id FROM demo_users WHERE id = ?", UUID.class, first);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_users (public_id, handle, display_name) VALUES (?, 'charlie', 'Charlie')", publicId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_users (handle, display_name) VALUES ('alpha', 'duplicate')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID typePublicId = jdbc.queryForObject("SELECT public_id FROM demo_task_types WHERE id=?", UUID.class, type);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_task_types (public_id, demo_user_id, name, color) VALUES (?, ?, 'duplicate', '#123ABC')", typePublicId, second))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID taskPublicId = jdbc.queryForObject("SELECT public_id FROM demo_tasks WHERE demo_task_type_id=?", UUID.class, type);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_tasks (public_id, demo_user_id, demo_task_type_id, title, due_date) VALUES (?, ?, ?, 'duplicate', CURRENT_DATE)", taskPublicId, first, type))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE demo_users SET fixture_key = 'fixture-one' WHERE id = ?", first);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_users SET fixture_key = 'fixture-one' WHERE id = ?", second))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE demo_task_types SET fixture_key = 'type-one' WHERE id=?", type);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_task_types (demo_user_id,name,color,fixture_key) VALUES (?, 'duplicate', '#123ABC', 'type-one')", second))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE demo_tasks SET fixture_key = 'task-one' WHERE demo_task_type_id=?", type);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_tasks (demo_user_id,demo_task_type_id,title,due_date,fixture_key) VALUES (?, ?, 'duplicate', CURRENT_DATE, 'task-one')", first, type))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO demo_task_types (demo_user_id,name,color) VALUES (?, 'second', '#123ABC')", second);
        jdbc.update("INSERT INTO demo_tasks (demo_user_id,demo_task_type_id,title,due_date) VALUES (?, ?, 'second', CURRENT_DATE)", first, type);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE fixture_key IS NULL", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_task_types WHERE fixture_key IS NULL", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_tasks WHERE fixture_key IS NULL", Integer.class)).isPositive();
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_users SET publication_status='INVALID' WHERE id=?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_task_types SET publication_status='INVALID' WHERE id=?", type))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_tasks SET publication_status='INVALID' WHERE demo_task_type_id=?", type))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_users SET handle='Alpha' WHERE id=?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_tasks SET urgency=3 WHERE demo_task_type_id=?", type))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_users SET publication_status='PUBLISHED' WHERE id=?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoriesPersistAndOptimisticLock() {
        DemoUser user = new DemoUser();
        user.setHandle("repository-user");
        user.setDisplayName("Repository User");
        user = users.saveAndFlush(user);
        assertThat(users.findByPublicId(user.getPublicId())).isPresent();
        assertThat(users.findByHandle("repository-user")).isPresent();
        assertThat(user.getVersion()).isZero();
        DemoTaskType type = new DemoTaskType();
        type.setDemoUserId(user.getId());
        type.setName("Work");
        type.setColor("#123ABC");
        type = types.saveAndFlush(type);
        assertThat(types.findByPublicId(type.getPublicId())).isPresent();
        DemoTask task = new DemoTask();
        task.setDemoUserId(user.getId());
        task.setDemoTaskTypeId(type.getId());
        task.setTitle("Example");
        task.setDueDate(LocalDate.now());
        task = tasks.saveAndFlush(task);
        assertThat(tasks.findByPublicId(task.getPublicId())).isPresent();
        assertThat(task.isCompleted()).isFalse();
        assertThat(control.findAll()).isEmpty();

        jdbc.update("UPDATE demo_users SET version=version+1 WHERE id=?", user.getId());
        user.setDisplayName("stale");
        DemoUser stale = user;
        assertThatThrownBy(() -> users.saveAndFlush(stale))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }

    private long insertUser(String handle) {
        jdbc.update("INSERT INTO demo_users (handle, display_name) VALUES (?, ?)", handle, handle);
        return jdbc.queryForObject("SELECT id FROM demo_users WHERE handle=?", Long.class, handle);
    }

    private long insertType(long userId) {
        jdbc.update("INSERT INTO demo_task_types (demo_user_id, name, color) VALUES (?, 'Work', '#123ABC')", userId);
        return jdbc.queryForObject("SELECT id FROM demo_task_types WHERE demo_user_id=?", Long.class, userId);
    }
}
