package com.tareas.app.demo.fixtures;

import com.tareas.app.admin.service.AdminAuditService;
import com.tareas.app.demo.fixtures.FixtureManifest.Document;
import com.tareas.app.demo.service.DemoHttpException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DemoFixtureService {
    private final JdbcTemplate db;
    private final FixtureManifest manifest;
    private final AdminAuditService audit;

    public record Changes(List<String> create, List<String> update, List<String> unchanged,
                          List<String> retired, List<String> conflicts) {}
    public record Preview(long currentRevision, long targetRevision, Integer currentManifestVersion,
                          int manifestVersion, String etag, Changes users, Changes types, Changes tasks,
                          List<String> catalogConflicts, long customRecords) {}
    public record RestoreResult(long previousRevision, long newRevision, int manifestVersion, UUID restoreId,
                                String etag, Changes users, Changes types, Changes tasks) {}
    private record Rows(Map<String, Map<String, Object>> byKey, List<Map<String, Object>> all) {}
    private record State(long revision, Integer appliedVersion, Rows users, Rows types, Rows tasks,
                         Map<String, Map<String, Object>> registry) {}

    @PreAuthorize("hasAuthority('DEMO_RESTORE')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview() {
        State state = state(false);
        return calculate(state);
    }

    @PreAuthorize("hasAuthority('DEMO_RESTORE')")
    @Transactional
    public RestoreResult restore(String ifMatch) {
        State state = state(true);
        requestAttribute("fixturePreviousRevision", state.revision());
        Preview before = calculate(state);
        requestAttribute("fixtureCounts", counts(before));
        if (ifMatch == null)
            throw new DemoHttpException(HttpStatus.PRECONDITION_REQUIRED, "If-Match es obligatorio");
        if (!ifMatch.equals(before.etag()))
            throw new DemoHttpException(HttpStatus.PRECONDITION_FAILED, "El catálogo cambió desde el preview");
        if (conflicts(before) > 0)
            throw new DemoHttpException(HttpStatus.CONFLICT, "El catálogo contiene conflictos; revisar preview");

        Document doc = manifest.document();
        Map<String, Long> userIds = ids(state, "USER");
        Map<String, Long> typeIds = ids(state, "TYPE");
        for (FixtureManifest.User u : doc.users()) {
            Long id = userIds.get(u.fixtureKey());
            if (id == null) {
                id = db.queryForObject("""
                        INSERT INTO demo_users (fixture_key, public_id, handle, display_name, bio)
                        VALUES (?, ?, ?, ?, ?) RETURNING id
                        """, Long.class, u.fixtureKey(), u.publicId(), u.handle(), u.displayName(), u.bio());
                register("USER", u.fixtureKey(), u.publicId(), id);
                userIds.put(u.fixtureKey(), id);
            } else if (before.users().update().contains(u.fixtureKey())) {
                db.update("""
                        UPDATE demo_users SET handle=?, display_name=?, bio=?, publication_status='DRAFT',
                            published_at=NULL, version=version+1, updated_at=now() WHERE id=?
                        """, u.handle(), u.displayName(), u.bio(), id);
            }
            reactivate("USER", u.fixtureKey());
        }
        for (FixtureManifest.Type t : doc.types()) {
            Long id = typeIds.get(t.fixtureKey());
            if (id == null) {
                id = db.queryForObject("""
                        INSERT INTO demo_task_types (fixture_key, public_id, demo_user_id, name, description, color)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                        """, Long.class, t.fixtureKey(), t.publicId(), userIds.get(t.userKey()),
                        t.name(), t.description(), t.color());
                register("TYPE", t.fixtureKey(), t.publicId(), id);
                typeIds.put(t.fixtureKey(), id);
            } else if (before.types().update().contains(t.fixtureKey())) {
                db.update("""
                        UPDATE demo_task_types SET demo_user_id=?, name=?, description=?, color=?,
                            publication_status='DRAFT', published_at=NULL, version=version+1, updated_at=now()
                        WHERE id=?
                        """, userIds.get(t.userKey()), t.name(), t.description(), t.color(), id);
            }
            reactivate("TYPE", t.fixtureKey());
        }
        Map<String, Long> taskIds = ids(state, "TASK");
        for (FixtureManifest.Task t : doc.tasks()) {
            Long id = taskIds.get(t.fixtureKey());
            if (id == null) {
                id = db.queryForObject("""
                        INSERT INTO demo_tasks (fixture_key, public_id, demo_user_id, demo_task_type_id,
                            title, description, due_date, completed, urgency)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """, Long.class, t.fixtureKey(), t.publicId(), userIds.get(t.userKey()),
                        typeIds.get(t.typeKey()), t.title(), t.description(), t.dueDate(), t.completed(), t.urgency());
                register("TASK", t.fixtureKey(), t.publicId(), id);
            } else if (before.tasks().update().contains(t.fixtureKey())) {
                db.update("""
                        UPDATE demo_tasks SET demo_user_id=?, demo_task_type_id=?, title=?, description=?,
                            due_date=?, completed=?, urgency=?, publication_status='DRAFT',
                            published_at=NULL, version=version+1, updated_at=now() WHERE id=?
                        """, userIds.get(t.userKey()), typeIds.get(t.typeKey()), t.title(),
                        t.description(), t.dueDate(), t.completed(), t.urgency(), id);
            }
            reactivate("TASK", t.fixtureKey());
        }
        // Retirement is a reversible unpublication. No rows are deleted.
        retire("TASK", before.tasks().retired(), state.tasks());
        retire("TYPE", before.types().retired(), state.types());
        retire("USER", before.users().retired(), state.users());
        long next = state.revision() + 1;
        UUID restoreId = UUID.randomUUID();
        db.update("""
                UPDATE demo_catalog_control SET revision=?, manifest_version=?, last_restored_at=now(),
                    last_restore_id=?, updated_at=now() WHERE id=1
                """, next, doc.version(), restoreId);
        audit.fixtureRestoreSuccess(state.revision(), next, counts(before));
        return new RestoreResult(state.revision(), next, doc.version(), restoreId, etag(state(false)),
                before.users(), before.types(), before.tasks());
    }

    private void retire(String kind, List<String> keys, Rows rows) {
        String table = switch (kind) {
            case "USER" -> "demo_users";
            case "TYPE" -> "demo_task_types";
            default -> "demo_tasks";
        };
        for (String key : keys) {
            Map<String, Object> row = rows.byKey().get(key);
            if (row != null && (!"DRAFT".equals(row.get("publication_status"))
                    || row.get("published_at") != null))
                db.update("UPDATE " + table + " SET publication_status='DRAFT', published_at=NULL, "
                        + "version=version+1, updated_at=now() WHERE id=?", row.get("id"));
            db.update("UPDATE demo_fixture_registry SET active=false WHERE kind=? AND fixture_key=?", kind, key);
        }
    }

    private void register(String kind, String key, UUID publicId, Long id) {
        db.update("INSERT INTO demo_fixture_registry (kind, fixture_key, public_id, row_id) VALUES (?, ?, ?, ?)",
                kind, key, publicId, id);
    }

    private void reactivate(String kind, String key) {
        db.update("UPDATE demo_fixture_registry SET active=true WHERE kind=? AND fixture_key=? AND active=false",
                kind, key);
    }

    private Map<String, Long> ids(State s, String kind) {
        Map<String, Long> result = new HashMap<>();
        s.registry().values().stream().filter(r -> kind.equals(r.get("kind")))
                .forEach(r -> result.put((String) r.get("fixture_key"), ((Number) r.get("row_id")).longValue()));
        return result;
    }

    private State state(boolean lock) {
        Map<String, Object> control = db.queryForMap(
                "SELECT revision, manifest_version FROM demo_catalog_control WHERE id=1"
                        + (lock ? " FOR UPDATE" : ""));
        Number version = (Number) control.get("manifest_version");
        return new State(((Number) control.get("revision")).longValue(),
                version == null ? null : version.intValue(),
                rows("demo_users"), rows("demo_task_types"), rows("demo_tasks"),
                index(db.queryForList("SELECT kind, fixture_key, public_id, row_id, active FROM demo_fixture_registry "
                        + "ORDER BY kind, fixture_key"), "kind", "fixture_key"));
    }

    private Rows rows(String table) {
        List<Map<String, Object>> all = db.queryForList("SELECT * FROM " + table + " ORDER BY id");
        return new Rows(index(all, "fixture_key"), all);
    }

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows, String... fields) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.get(fields[fields.length - 1]) != null) {
                String key = fields.length == 1 ? row.get(fields[0]).toString()
                        : row.get(fields[0]) + ":" + row.get(fields[1]);
                result.put(key, row);
            }
        }
        return result;
    }

    private Preview calculate(State s) {
        Document d = manifest.document();
        Changes u = changes("USER", s.users(), s.registry(), d.users().stream().collect(
                LinkedHashMap::new, (m, x) -> m.put(x.fixtureKey(), Map.<String, Object>of(
                        "public_id", x.publicId(), "handle", x.handle(), "display_name", x.displayName())),
                Map::putAll), d.users().stream().collect(LinkedHashMap::new,
                (m, x) -> m.put(x.fixtureKey(), x.bio()), Map::putAll));
        Changes ty = changes("TYPE", s.types(), s.registry(), d.types().stream().collect(
                LinkedHashMap::new, (m, x) -> m.put(x.fixtureKey(), Map.<String, Object>of(
                        "public_id", x.publicId(), "user_key", x.userKey(), "name", x.name(), "color", x.color())),
                Map::putAll), d.types().stream().collect(LinkedHashMap::new,
                (m, x) -> m.put(x.fixtureKey(), x.description()), Map::putAll));
        Changes ta = changes("TASK", s.tasks(), s.registry(), d.tasks().stream().collect(
                LinkedHashMap::new, (m, x) -> m.put(x.fixtureKey(), Map.<String, Object>of(
                        "public_id", x.publicId(), "user_key", x.userKey(), "type_key", x.typeKey(),
                        "title", x.title(), "due_date", x.dueDate(), "completed", x.completed(),
                        "urgency", x.urgency())), Map::putAll), d.tasks().stream().collect(
                LinkedHashMap::new, (m, x) -> m.put(x.fixtureKey(), x.description()), Map::putAll));
        protectPublishedCustomChildren(s, u, ty);
        long custom = s.users().all().stream().filter(r -> !s.registry().containsKey("USER:" + r.get("fixture_key"))).count()
                + s.types().all().stream().filter(r -> !s.registry().containsKey("TYPE:" + r.get("fixture_key"))).count()
                + s.tasks().all().stream().filter(r -> !s.registry().containsKey("TASK:" + r.get("fixture_key"))).count();
        List<String> catalogConflicts = s.appliedVersion() != null && s.appliedVersion() > d.version()
                ? List.of("manifest_version_older_than_applied") : List.of();
        return new Preview(s.revision(), s.revision() + 1, s.appliedVersion(), d.version(),
                etag(s), u, ty, ta, catalogConflicts, custom);
    }

    private void protectPublishedCustomChildren(State s, Changes users, Changes types) {
        for (String key : new ArrayList<>(users.update())) {
            Map<String, Object> parent = s.users().byKey().get(key);
            if (parent != null && "PUBLISHED".equals(parent.get("publication_status"))
                    && hasPublishedCustomChildren(s, "USER", ((Number) parent.get("id")).longValue())) {
                users.update().remove(key);
                users.conflicts().add(key);
            }
        }
        for (String key : new ArrayList<>(users.retired())) {
            Map<String, Object> parent = s.users().byKey().get(key);
            if (parent != null && "PUBLISHED".equals(parent.get("publication_status"))
                    && hasPublishedCustomChildren(s, "USER", ((Number) parent.get("id")).longValue())) {
                users.retired().remove(key);
                users.conflicts().add(key);
            }
        }
        for (String key : new ArrayList<>(types.update())) {
            Map<String, Object> parent = s.types().byKey().get(key);
            if (parent != null && "PUBLISHED".equals(parent.get("publication_status"))
                    && hasPublishedCustomChildren(s, "TYPE", ((Number) parent.get("id")).longValue())) {
                types.update().remove(key);
                types.conflicts().add(key);
            }
        }
        for (String key : new ArrayList<>(types.retired())) {
            Map<String, Object> parent = s.types().byKey().get(key);
            if (parent != null && "PUBLISHED".equals(parent.get("publication_status"))
                    && hasPublishedCustomChildren(s, "TYPE", ((Number) parent.get("id")).longValue())) {
                types.retired().remove(key);
                types.conflicts().add(key);
            }
        }
    }

    private boolean hasPublishedCustomChildren(State s, String kind, long id) {
        if (kind.equals("USER")) {
            boolean customType = s.types().all().stream().anyMatch(row ->
                    ((Number) row.get("demo_user_id")).longValue() == id
                            && "PUBLISHED".equals(row.get("publication_status"))
                            && !s.registry().containsKey("TYPE:" + row.get("fixture_key")));
            if (customType) return true;
        }
        return s.tasks().all().stream().anyMatch(row ->
                ((Number) row.get(kind.equals("USER") ? "demo_user_id" : "demo_task_type_id")).longValue() == id
                        && "PUBLISHED".equals(row.get("publication_status"))
                        && !s.registry().containsKey("TASK:" + row.get("fixture_key")));
    }

    private Changes changes(String kind, Rows rows, Map<String, Map<String, Object>> registry,
                            Map<String, Map<String, Object>> desired, Map<String, String> optional) {
        List<String> create = new ArrayList<>(), update = new ArrayList<>(), unchanged = new ArrayList<>();
        List<String> retired = new ArrayList<>(), conflicts = new ArrayList<>();
        for (var entry : desired.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> reg = registry.get(kind + ":" + key);
            Map<String, Object> row = rows.byKey().get(key);
            UUID publicId = (UUID) entry.getValue().get("public_id");
            if (reg == null) {
                boolean claimed = row != null || rows.all().stream().anyMatch(r -> publicId.equals(r.get("public_id")));
                if (kind.equals("USER")) claimed |= rows.all().stream().anyMatch(
                        r -> Objects.equals(r.get("handle"), entry.getValue().get("handle")));
                (claimed ? conflicts : create).add(key);
            } else if (!identityMatches(reg, row, publicId)) {
                conflicts.add(key);
            } else if (kind.equals("USER") && rows.all().stream().anyMatch(r ->
                    !Objects.equals(r.get("id"), row.get("id"))
                            && Objects.equals(r.get("handle"), entry.getValue().get("handle")))) {
                conflicts.add(key);
            } else if (kind.equals("TYPE") && !Objects.equals(
                    fixtureKeyAt("USER", row.get("demo_user_id")), entry.getValue().get("user_key"))
                    && db.queryForObject("SELECT count(*) FROM demo_tasks WHERE demo_task_type_id=?",
                    Long.class, row.get("id")) > 0) {
                conflicts.add(key);
            } else {
                boolean changed = !Boolean.TRUE.equals(reg.get("active"))
                        || !"DRAFT".equals(row.get("publication_status"))
                        || row.get("published_at") != null
                        || !Objects.equals(row.get(kind.equals("USER") ? "bio" : "description"), optional.get(key));
                for (var field : entry.getValue().entrySet()) {
                    if (field.getKey().equals("public_id")) continue;
                    Object actual = switch (field.getKey()) {
                        case "user_key" -> fixtureKeyAt("USER", row.get("demo_user_id"));
                        case "type_key" -> fixtureKeyAt("TYPE", row.get("demo_task_type_id"));
                        default -> row.get(field.getKey());
                    };
                    if (actual instanceof Date date) actual = date.toLocalDate();
                    if (!Objects.equals(actual, field.getValue())) changed = true;
                }
                (changed ? update : unchanged).add(key);
            }
        }
        for (var reg : registry.values()) {
            if (!kind.equals(reg.get("kind")) || desired.containsKey(reg.get("fixture_key"))) continue;
            String key = (String) reg.get("fixture_key");
            Map<String, Object> row = rows.byKey().get(key);
            if (!identityMatches(reg, row, (UUID) reg.get("public_id"))) conflicts.add(key);
            else retired.add(key);
        }
        return new Changes(create, update, unchanged, retired, conflicts);
    }

    private String fixtureKeyAt(String kind, Object id) {
        if (id == null) return null;
        String table = kind.equals("USER") ? "demo_users" : "demo_task_types";
        return db.queryForObject("SELECT fixture_key FROM " + table + " WHERE id=?", String.class, id);
    }

    private boolean identityMatches(Map<String, Object> reg, Map<String, Object> row, UUID desiredPublicId) {
        return row != null && Objects.equals(reg.get("public_id"), desiredPublicId)
                && Objects.equals(row.get("public_id"), desiredPublicId)
                && ((Number) reg.get("row_id")).longValue() == ((Number) row.get("id")).longValue();
    }

    private String etag(State s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : List.of(s.revision(), Objects.toString(s.appliedVersion()), s.users().all(),
                    s.types().all(), s.tasks().all(),
                    s.registry().values()))
                digest.update(part.toString().getBytes(StandardCharsets.UTF_8));
            return "\"v" + s.revision() + "-" + HexFormat.of().formatHex(digest.digest()) + "\"";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int conflicts(Preview p) {
        return p.users().conflicts().size() + p.types().conflicts().size()
                + p.tasks().conflicts().size() + p.catalogConflicts().size();
    }

    private String counts(Preview p) {
        int create = p.users().create().size() + p.types().create().size() + p.tasks().create().size();
        int update = p.users().update().size() + p.types().update().size() + p.tasks().update().size();
        int unchanged = p.users().unchanged().size() + p.types().unchanged().size() + p.tasks().unchanged().size();
        int retired = p.users().retired().size() + p.types().retired().size() + p.tasks().retired().size();
        return "create=" + create + ",update=" + update + ",delete=0,unchanged=" + unchanged + ",retired=" + retired;
    }

    private void requestAttribute(String key, Object value) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            attributes.getRequest().setAttribute(key, value);
    }
}
