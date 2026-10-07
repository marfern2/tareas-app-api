package com.tareas.app.demo.service;

import com.tareas.app.admin.service.AdminAuditService;
import com.tareas.app.demo.dto.admin.DemoAdminDtos.*;
import com.tareas.app.demo.model.*;
import com.tareas.app.demo.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DemoAdminService {
    private final DemoUserRepository users;
    private final DemoTaskTypeRepository types;
    private final DemoTaskRepository tasks;
    private final AdminAuditService audit;
    private final DemoValidation v;

    private static <T> Specification<T> eq(String property, Object value) {
        return (root, query, cb) -> value == null ? cb.conjunction() : cb.equal(root.get(property), value);
    }

    private static <T> Specification<T> contains(String property, String value) {
        return (root, query, cb) -> value == null || value.isBlank() ? cb.conjunction()
                : cb.like(cb.lower(root.get(property)), "%" + escape(value.trim().toLowerCase(Locale.ROOT)) + "%", '\\');
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public Page<UserView> users(String search, PublicationStatus status, Pageable page) {
        Specification<DemoUser> s = eq("publicationStatus", status);
        if (search != null && !search.isBlank()) {
            String q = "%" + escape(search.trim().toLowerCase(Locale.ROOT)) + "%";
            s = s.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("handle")), q, '\\'),
                    cb.like(cb.lower(root.get("displayName")), q, '\\')));
        }
        return users.findAll(s, page).map(this::view);
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public UserView user(Long id) { return view(users.findById(id).orElseThrow(v::missing)); }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public UserView createUser(UserCreate request) {
        String handle = v.handle(request.handle());
        if (users.existsByHandle(handle)) throw v.conflict("handle ya existe");
        DemoUser user = new DemoUser();
        user.setHandle(handle);
        user.setDisplayName(v.text(request.displayName(), "displayName", 2, 80));
        user.setBio(v.text(request.bio(), "bio", 0, 500));
        try { user = users.saveAndFlush(user); }
        catch (DataIntegrityViolationException ex) { throw v.conflict("handle ya existe"); }
        audit.success("DEMO_USER_CREATE", "DEMO_USER", user.getId());
        return view(user);
    }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public UserView updateUser(Long id, long expected, UserPatch request) {
        DemoUser user = users.lockById(id).orElseThrow(v::missing);
        v.version(expected, user.getVersion());
        if (request.handle() != null) {
            String handle = v.handle(request.handle());
            if (users.existsByHandleAndIdNot(handle, id)) throw v.conflict("handle ya existe");
            user.setHandle(handle);
        }
        if (request.displayName() != null) user.setDisplayName(v.text(request.displayName(), "displayName", 2, 80));
        if (request.bio() != null) user.setBio(v.text(request.bio(), "bio", 0, 500));
        try { users.flush(); }
        catch (DataIntegrityViolationException ex) { throw v.conflict("handle ya existe"); }
        audit.success("DEMO_USER_UPDATE", "DEMO_USER", id);
        return view(user);
    }

    @PreAuthorize("hasAuthority('DEMO_PUBLISH')")
    @Transactional
    public UserView publishUser(Long id, long expected, PublicationStatus status) {
        v.status(status);
        DemoUser user = users.lockById(id).orElseThrow(v::missing);
        v.version(expected, user.getVersion());
        if (status == PublicationStatus.DRAFT &&
                (types.existsByDemoUserIdAndPublicationStatus(id, PublicationStatus.PUBLISHED)
                        || tasks.existsByDemoUserIdAndPublicationStatus(id, PublicationStatus.PUBLISHED)))
            throw v.conflict("Hay tipos o tareas publicados");
        setStatus(user, status);
        users.flush();
        audit.success(status == PublicationStatus.PUBLISHED ? "DEMO_USER_PUBLISH" : "DEMO_USER_UNPUBLISH", "DEMO_USER", id);
        return view(user);
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public Page<TypeView> types(Long userId, String search, PublicationStatus status, Pageable page) {
        return types.findAll(DemoAdminService.<DemoTaskType>eq("demoUserId", userId).and(eq("publicationStatus", status))
                .and(contains("name", search)), page).map(this::view);
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public TypeView type(Long id) { return view(types.findById(id).orElseThrow(v::missing)); }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public TypeView createType(TypeCreate request) {
        requirePositive(request.demoUserId(), "demoUserId");
        users.lockById(request.demoUserId()).orElseThrow(v::missing);
        DemoTaskType type = new DemoTaskType();
        type.setDemoUserId(request.demoUserId());
        type.setName(v.text(request.name(), "name", 2, 50));
        type.setDescription(v.text(request.description(), "description", 0, 500));
        type.setColor(v.color(request.color()));
        type = types.saveAndFlush(type);
        audit.success("DEMO_TASK_TYPE_CREATE", "DEMO_TASK_TYPE", type.getId());
        return view(type);
    }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public TypeView updateType(Long id, long expected, TypePatch request) {
        DemoTaskType snapshot = types.findById(id).orElseThrow(v::missing);
        DemoUser owner = users.lockById(snapshot.getDemoUserId()).orElseThrow(v::missing);
        DemoTaskType type = types.lockById(id).orElseThrow(v::missing);
        v.version(expected, type.getVersion());
        if (request.demoUserId() != null && !Objects.equals(request.demoUserId(), owner.getId()))
            throw v.conflict("demoUserId no puede cambiarse");
        if (request.name() != null) type.setName(v.text(request.name(), "name", 2, 50));
        if (request.description() != null) type.setDescription(v.text(request.description(), "description", 0, 500));
        if (request.color() != null) type.setColor(v.color(request.color()));
        types.flush();
        audit.success("DEMO_TASK_TYPE_UPDATE", "DEMO_TASK_TYPE", id);
        return view(type);
    }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public void deleteType(Long id, long expected) {
        DemoTaskType snapshot = types.findById(id).orElseThrow(v::missing);
        users.lockById(snapshot.getDemoUserId()).orElseThrow(v::missing);
        DemoTaskType type = types.lockById(id).orElseThrow(v::missing);
        v.version(expected, type.getVersion());
        if (tasks.existsByDemoTaskTypeId(id)) throw v.conflict("El tipo tiene tareas asociadas");
        types.delete(type);
        types.flush();
        audit.success("DEMO_TASK_TYPE_DELETE", "DEMO_TASK_TYPE", id);
    }

    @PreAuthorize("hasAuthority('DEMO_PUBLISH')")
    @Transactional
    public TypeView publishType(Long id, long expected, PublicationStatus status) {
        v.status(status);
        DemoTaskType snapshot = types.findById(id).orElseThrow(v::missing);
        DemoUser owner = users.lockById(snapshot.getDemoUserId()).orElseThrow(v::missing);
        DemoTaskType type = types.lockById(id).orElseThrow(v::missing);
        v.version(expected, type.getVersion());
        if (status == PublicationStatus.PUBLISHED && owner.getPublicationStatus() != PublicationStatus.PUBLISHED)
            throw v.conflict("El usuario demo debe estar publicado");
        if (status == PublicationStatus.DRAFT && tasks.existsByDemoTaskTypeIdAndPublicationStatus(id, PublicationStatus.PUBLISHED))
            throw v.conflict("El tipo tiene tareas publicadas");
        setStatus(type, status);
        types.flush();
        audit.success(status == PublicationStatus.PUBLISHED ? "DEMO_TASK_TYPE_PUBLISH" : "DEMO_TASK_TYPE_UNPUBLISH", "DEMO_TASK_TYPE", id);
        return view(type);
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public Page<TaskView> tasks(Long userId, Long typeId, Boolean completed, Integer urgency,
                                PublicationStatus status, String search, Pageable page) {
        if (urgency != null) v.urgency(urgency);
        return tasks.findAll(DemoAdminService.<DemoTask>eq("demoUserId", userId).and(eq("demoTaskTypeId", typeId))
                .and(eq("completed", completed)).and(eq("urgency", urgency))
                .and(eq("publicationStatus", status)).and(contains("title", search)), page).map(this::view);
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public TaskView task(Long id) { return view(tasks.findById(id).orElseThrow(v::missing)); }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public TaskView createTask(TaskCreate request) {
        requirePositive(request.demoUserId(), "demoUserId");
        requirePositive(request.demoTaskTypeId(), "demoTaskTypeId");
        users.lockById(request.demoUserId()).orElseThrow(v::missing);
        DemoTaskType type = types.lockById(request.demoTaskTypeId()).orElseThrow(v::missing);
        if (!type.getDemoUserId().equals(request.demoUserId())) throw v.conflict("El tipo pertenece a otro usuario demo");
        DemoTask task = new DemoTask();
        task.setDemoUserId(request.demoUserId());
        task.setDemoTaskTypeId(request.demoTaskTypeId());
        task.setTitle(v.text(request.title(), "title", 3, 100));
        task.setDescription(v.text(request.description(), "description", 0, 500));
        task.setDueDate(requireDate(request.dueDate()));
        task.setCompleted(Objects.requireNonNull(request.completed()));
        task.setUrgency(v.urgency(request.urgency()));
        task = tasks.saveAndFlush(task);
        audit.success("DEMO_TASK_CREATE", "DEMO_TASK", task.getId());
        return view(task);
    }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public TaskView updateTask(Long id, long expected, TaskPatch request) {
        DemoTask snapshot = tasks.findById(id).orElseThrow(v::missing);
        users.lockById(snapshot.getDemoUserId()).orElseThrow(v::missing);
        types.lockById(snapshot.getDemoTaskTypeId()).orElseThrow(v::missing);
        DemoTask task = tasks.lockById(id).orElseThrow(v::missing);
        v.version(expected, task.getVersion());
        if (request.demoUserId() != null && !request.demoUserId().equals(task.getDemoUserId()))
            throw v.conflict("demoUserId no puede cambiarse");
        if (request.demoTaskTypeId() != null && !request.demoTaskTypeId().equals(task.getDemoTaskTypeId()))
            throw v.conflict("demoTaskTypeId no puede cambiarse");
        if (request.title() != null) task.setTitle(v.text(request.title(), "title", 3, 100));
        if (request.description() != null) task.setDescription(v.text(request.description(), "description", 0, 500));
        if (request.dueDate() != null) task.setDueDate(request.dueDate());
        if (request.completed() != null) task.setCompleted(request.completed());
        if (request.urgency() != null) task.setUrgency(v.urgency(request.urgency()));
        tasks.flush();
        audit.success("DEMO_TASK_UPDATE", "DEMO_TASK", id);
        return view(task);
    }

    @PreAuthorize("hasAuthority('DEMO_WRITE')")
    @Transactional
    public void deleteTask(Long id, long expected) {
        DemoTask snapshot = tasks.findById(id).orElseThrow(v::missing);
        users.lockById(snapshot.getDemoUserId()).orElseThrow(v::missing);
        types.lockById(snapshot.getDemoTaskTypeId()).orElseThrow(v::missing);
        DemoTask task = tasks.lockById(id).orElseThrow(v::missing);
        v.version(expected, task.getVersion());
        tasks.delete(task);
        tasks.flush();
        audit.success("DEMO_TASK_DELETE", "DEMO_TASK", id);
    }

    @PreAuthorize("hasAuthority('DEMO_PUBLISH')")
    @Transactional
    public TaskView publishTask(Long id, long expected, PublicationStatus status) {
        v.status(status);
        DemoTask snapshot = tasks.findById(id).orElseThrow(v::missing);
        DemoUser owner = users.lockById(snapshot.getDemoUserId()).orElseThrow(v::missing);
        DemoTaskType type = types.lockById(snapshot.getDemoTaskTypeId()).orElseThrow(v::missing);
        DemoTask task = tasks.lockById(id).orElseThrow(v::missing);
        v.version(expected, task.getVersion());
        if (status == PublicationStatus.PUBLISHED && (owner.getPublicationStatus() != PublicationStatus.PUBLISHED
                || type.getPublicationStatus() != PublicationStatus.PUBLISHED
                || !type.getDemoUserId().equals(task.getDemoUserId())))
            throw v.conflict("Usuario y tipo deben estar publicados y pertenecer al mismo catálogo");
        setStatus(task, status);
        tasks.flush();
        audit.success(status == PublicationStatus.PUBLISHED ? "DEMO_TASK_PUBLISH" : "DEMO_TASK_UNPUBLISH", "DEMO_TASK", id);
        return view(task);
    }

    @PreAuthorize("hasAuthority('DEMO_READ')")
    @Transactional(readOnly = true)
    public Stats stats() {
        return new Stats(users.count(), users.countByPublicationStatus(PublicationStatus.PUBLISHED),
                types.count(), types.countByPublicationStatus(PublicationStatus.PUBLISHED),
                tasks.count(), tasks.countByPublicationStatus(PublicationStatus.PUBLISHED), tasks.countByCompletedTrue());
    }

    private void requirePositive(Long id, String field) {
        if (id == null || id < 1) throw v.bad(field + " debe ser positivo");
    }
    private LocalDate requireDate(LocalDate date) {
        if (date == null) throw v.bad("dueDate es obligatorio");
        return date;
    }
    private void setStatus(DemoUser x, PublicationStatus s) {
        if (x.getPublicationStatus() != s) { x.setPublicationStatus(s); x.setPublishedAt(s == PublicationStatus.PUBLISHED ? Instant.now() : null); }
    }
    private void setStatus(DemoTaskType x, PublicationStatus s) {
        if (x.getPublicationStatus() != s) { x.setPublicationStatus(s); x.setPublishedAt(s == PublicationStatus.PUBLISHED ? Instant.now() : null); }
    }
    private void setStatus(DemoTask x, PublicationStatus s) {
        if (x.getPublicationStatus() != s) { x.setPublicationStatus(s); x.setPublishedAt(s == PublicationStatus.PUBLISHED ? Instant.now() : null); }
    }
    private UserView view(DemoUser x) { return new UserView(x.getId(), x.getPublicId(), x.getHandle(), x.getDisplayName(), x.getBio(), x.getPublicationStatus(), x.getPublishedAt(), x.getVersion(), x.getCreatedAt(), x.getUpdatedAt()); }
    private TypeView view(DemoTaskType x) { return new TypeView(x.getId(), x.getPublicId(), x.getDemoUserId(), x.getName(), x.getDescription(), x.getColor(), x.getPublicationStatus(), x.getPublishedAt(), x.getVersion(), x.getCreatedAt(), x.getUpdatedAt()); }
    private TaskView view(DemoTask x) { return new TaskView(x.getId(), x.getPublicId(), x.getDemoUserId(), x.getDemoTaskTypeId(), x.getTitle(), x.getDescription(), x.getDueDate(), x.isCompleted(), x.getUrgency(), x.getPublicationStatus(), x.getPublishedAt(), x.getVersion(), x.getCreatedAt(), x.getUpdatedAt()); }
}
