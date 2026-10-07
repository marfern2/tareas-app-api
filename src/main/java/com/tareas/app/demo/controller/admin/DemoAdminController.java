package com.tareas.app.demo.controller.admin;

import com.tareas.app.demo.dto.admin.DemoAdminDtos.*;
import com.tareas.app.demo.model.PublicationStatus;
import com.tareas.app.demo.service.DemoAdminService;
import com.tareas.app.demo.service.DemoValidation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/demo")
@RequiredArgsConstructor
public class DemoAdminController {
    private final DemoAdminService service;
    private final DemoValidation validation;

    private <T> ResponseEntity<T> entity(T body, long version) {
        return ResponseEntity.ok().eTag("\"v" + version + "\"").body(body);
    }
    private <T> ResponseEntity<T> created(String path, Long id, T body, long version) {
        return ResponseEntity.created(URI.create(path + "/" + id)).eTag("\"v" + version + "\"").body(body);
    }

    @GetMapping("/users")
    public Page<UserView> users(@RequestParam(required = false) String search,
                                @RequestParam(required = false) PublicationStatus publicationStatus,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(defaultValue = "id,asc") String sort) {
        return service.users(search, publicationStatus,
                validation.page(page, size, sort, Set.of("id", "handle", "displayName", "createdAt", "updatedAt", "publicationStatus")));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserView> user(@PathVariable Long id) {
        UserView x = service.user(id); return entity(x, x.version());
    }

    @PostMapping("/users")
    public ResponseEntity<UserView> createUser(@Valid @RequestBody UserCreate request) {
        UserView x = service.createUser(request); return created("/api/admin/demo/users", x.id(), x, x.version());
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<UserView> updateUser(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody UserPatch request) {
        UserView x = service.updateUser(id, validation.ifMatch(ifMatch), request); return entity(x, x.version());
    }

    @PatchMapping("/users/{id}/publication")
    public ResponseEntity<UserView> publishUser(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                @Valid @RequestBody Publication request) {
        UserView x = service.publishUser(id, validation.ifMatch(ifMatch), request.publicationStatus()); return entity(x, x.version());
    }

    @GetMapping("/task-types")
    public Page<TypeView> types(@RequestParam(required = false) Long demoUserId,
                                @RequestParam(required = false) String search,
                                @RequestParam(required = false) PublicationStatus publicationStatus,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(defaultValue = "id,asc") String sort) {
        return service.types(demoUserId, search, publicationStatus,
                validation.page(page, size, sort, Set.of("id", "name", "createdAt", "updatedAt", "publicationStatus")));
    }

    @GetMapping("/task-types/{id}")
    public ResponseEntity<TypeView> type(@PathVariable Long id) {
        TypeView x = service.type(id); return entity(x, x.version());
    }

    @PostMapping("/task-types")
    public ResponseEntity<TypeView> createType(@Valid @RequestBody TypeCreate request) {
        TypeView x = service.createType(request); return created("/api/admin/demo/task-types", x.id(), x, x.version());
    }

    @PatchMapping("/task-types/{id}")
    public ResponseEntity<TypeView> updateType(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody TypePatch request) {
        TypeView x = service.updateType(id, validation.ifMatch(ifMatch), request); return entity(x, x.version());
    }

    @DeleteMapping("/task-types/{id}")
    public ResponseEntity<Void> deleteType(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.deleteType(id, validation.ifMatch(ifMatch)); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/task-types/{id}/publication")
    public ResponseEntity<TypeView> publishType(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                @Valid @RequestBody Publication request) {
        TypeView x = service.publishType(id, validation.ifMatch(ifMatch), request.publicationStatus()); return entity(x, x.version());
    }

    @GetMapping("/tasks")
    public Page<TaskView> tasks(@RequestParam(required = false) Long demoUserId,
                                @RequestParam(required = false) Long demoTaskTypeId,
                                @RequestParam(required = false) Boolean completed,
                                @RequestParam(required = false) Integer urgency,
                                @RequestParam(required = false) PublicationStatus publicationStatus,
                                @RequestParam(required = false) String search,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(defaultValue = "id,asc") String sort) {
        return service.tasks(demoUserId, demoTaskTypeId, completed, urgency, publicationStatus, search,
                validation.page(page, size, sort, Set.of("id", "title", "dueDate", "urgency", "completed", "createdAt", "updatedAt", "publicationStatus")));
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<TaskView> task(@PathVariable Long id) {
        TaskView x = service.task(id); return entity(x, x.version());
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskView> createTask(@Valid @RequestBody TaskCreate request) {
        TaskView x = service.createTask(request); return created("/api/admin/demo/tasks", x.id(), x, x.version());
    }

    @PatchMapping("/tasks/{id}")
    public ResponseEntity<TaskView> updateTask(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody TaskPatch request) {
        TaskView x = service.updateTask(id, validation.ifMatch(ifMatch), request); return entity(x, x.version());
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.deleteTask(id, validation.ifMatch(ifMatch)); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/tasks/{id}/publication")
    public ResponseEntity<TaskView> publishTask(@PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                @Valid @RequestBody Publication request) {
        TaskView x = service.publishTask(id, validation.ifMatch(ifMatch), request.publicationStatus()); return entity(x, x.version());
    }

    @GetMapping("/stats")
    public Stats stats() { return service.stats(); }
}
