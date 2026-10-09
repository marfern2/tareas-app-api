package com.tareas.app.demo;

import com.tareas.app.demo.fixtures.FixtureManifest;
import com.tareas.app.demo.service.DemoValidation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureManifestValidationTest {
    private final DemoValidation validation = new DemoValidation();
    private final UUID userId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final FixtureManifest.User user =
            new FixtureManifest.User("catalog:user:one", userId, "demo-one", "Demo One", "Synthetic");
    private final FixtureManifest.Type type =
            new FixtureManifest.Type("catalog:type:one", typeId, user.fixtureKey(), "Planning", "Synthetic", "#AABBCC");
    private final FixtureManifest.Task task =
            new FixtureManifest.Task("catalog:task:one", taskId, user.fixtureKey(), type.fixtureKey(),
                    "Example task", "Synthetic", LocalDate.of(2027, 1, 1), false, 1);

    private void invalid(List<FixtureManifest.User> users, List<FixtureManifest.Type> types,
                         List<FixtureManifest.Task> tasks) {
        assertThatThrownBy(() -> FixtureManifest.validate(
                new FixtureManifest.Document(1, users, types, tasks), validation))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void duplicateKeysAndIds() {
        invalid(List.of(user, user), List.of(type), List.of(task));
        invalid(List.of(user), List.of(new FixtureManifest.Type("catalog:type:one", userId,
                user.fixtureKey(), "Planning", null, "#AABBCC")), List.of(task));
    }

    @Test void missingOrCrossOwnerReferences() {
        invalid(List.of(user), List.of(type), List.of(new FixtureManifest.Task(task.fixtureKey(), taskId,
                user.fixtureKey(), "catalog:type:missing", "Example task", null, task.dueDate(), false, 1)));
        var other = new FixtureManifest.User("catalog:user:other", UUID.randomUUID(), "demo-other", "Other Demo", null);
        invalid(List.of(user, other), List.of(type), List.of(new FixtureManifest.Task(task.fixtureKey(), taskId,
                other.fixtureKey(), type.fixtureKey(), "Example task", null, task.dueDate(), false, 1)));
    }

    @Test void invalidFields() {
        invalid(List.of(user), List.of(new FixtureManifest.Type(type.fixtureKey(), typeId, user.fixtureKey(),
                "Planning", null, "red")), List.of(task));
        invalid(List.of(user), List.of(type), List.of(new FixtureManifest.Task(task.fixtureKey(), taskId,
                user.fixtureKey(), type.fixtureKey(), "Example task", null, task.dueDate(), false, 3)));
        invalid(List.of(new FixtureManifest.User(user.fixtureKey(), userId, user.handle(), " ", null)),
                List.of(type), List.of(task));
        invalid(List.of(user), List.of(type), List.of(new FixtureManifest.Task(task.fixtureKey(), taskId,
                user.fixtureKey(), type.fixtureKey(), "Example task", null, task.dueDate(), null, 1)));
    }
}
