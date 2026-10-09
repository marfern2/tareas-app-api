package com.tareas.app.demo.fixtures;

import com.tareas.app.demo.service.DemoValidation;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class FixtureManifest {
    public record User(String fixtureKey, UUID publicId, String handle, String displayName, String bio) {}
    public record Type(String fixtureKey, UUID publicId, String userKey, String name, String description, String color) {}
    public record Task(String fixtureKey, UUID publicId, String userKey, String typeKey, String title,
                       String description, LocalDate dueDate, Boolean completed, Integer urgency) {}
    public record Document(int version, List<User> users, List<Type> types, List<Task> tasks) {}

    private final Document document;

    public FixtureManifest(ObjectMapper mapper, DemoValidation validation) throws IOException {
        document = mapper.readValue(new ClassPathResource("demo/catalog-v1.json").getInputStream(), Document.class);
        validate(document, validation);
    }

    public Document document() { return document; }

    public static void validate(Document doc, DemoValidation v) {
        if (doc == null || doc.version() < 1 || doc.users() == null || doc.types() == null || doc.tasks() == null)
            throw new IllegalStateException("Manifest de fixtures incompleto");
        Set<String> keys = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        Map<String, User> users = new HashMap<>();
        Map<String, Type> types = new HashMap<>();
        Set<String> handles = new HashSet<>();
        try {
            for (User u : doc.users()) {
                identity(u.fixtureKey(), u.publicId(), keys, ids);
                kind(u.fixtureKey(), "user");
                if (!u.handle().equals(v.handle(u.handle()))) throw new IllegalArgumentException("handle no normalizado");
                if (!handles.add(u.handle())) throw new IllegalArgumentException("handle duplicado");
                v.text(u.displayName(), "displayName", 2, 80);
                v.text(u.bio(), "bio", 0, 500);
                users.put(u.fixtureKey(), u);
            }
            for (Type t : doc.types()) {
                identity(t.fixtureKey(), t.publicId(), keys, ids);
                kind(t.fixtureKey(), "type");
                if (!users.containsKey(t.userKey())) throw new IllegalArgumentException("usuario inexistente");
                v.text(t.name(), "name", 2, 50);
                v.text(t.description(), "description", 0, 500);
                if (!t.color().equals(v.color(t.color()))) throw new IllegalArgumentException("color no normalizado");
                types.put(t.fixtureKey(), t);
            }
            for (Task t : doc.tasks()) {
                identity(t.fixtureKey(), t.publicId(), keys, ids);
                kind(t.fixtureKey(), "task");
                if (!users.containsKey(t.userKey())) throw new IllegalArgumentException("usuario inexistente");
                Type type = types.get(t.typeKey());
                if (type == null || !type.userKey().equals(t.userKey()))
                    throw new IllegalArgumentException("tipo inexistente o de otro usuario");
                v.text(t.title(), "title", 3, 100);
                v.text(t.description(), "description", 0, 500);
                if (t.dueDate() == null || t.completed() == null) throw new IllegalArgumentException("tarea incompleta");
                v.urgency(t.urgency());
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Manifest de fixtures inválido: " + ex.getMessage(), ex);
        }
    }

    private static void identity(String key, UUID id, Set<String> keys, Set<UUID> ids) {
        if (key == null || !key.matches("catalog:(user|type|task):[a-z0-9:-]{1,67}") || key.length() > 80
                || id == null || !keys.add(key) || !ids.add(id))
            throw new IllegalArgumentException("fixture_key o publicId inválido o duplicado");
    }

    private static void kind(String key, String expected) {
        if (!key.startsWith("catalog:" + expected + ":"))
            throw new IllegalArgumentException("fixture_key de tipo incorrecto");
    }
}
