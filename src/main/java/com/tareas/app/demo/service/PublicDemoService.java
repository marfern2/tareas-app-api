package com.tareas.app.demo.service;

import com.tareas.app.demo.dto.publicapi.PublicDemoDtos.*;
import com.tareas.app.demo.repository.PublicDemoTaskRepository;
import com.tareas.app.demo.repository.PublicDemoTaskTypeRepository;
import com.tareas.app.demo.repository.PublicDemoUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicDemoService {
    private final PublicDemoUserRepository users;
    private final PublicDemoTaskTypeRepository types;
    private final PublicDemoTaskRepository tasks;

    public PublicDemoPage<PublicDemoUser> users(MultiValueMap<String, String> params) {
        validateKeys(params, Set.of("page", "size", "sort", "search"));
        return page(users.visible(search(params), paging(params, Set.of("handle", "displayName"), "handle")));
    }

    public PublicDemoUser user(String id, MultiValueMap<String, String> params) {
        validateKeys(params, Set.of());
        return users.visibleByPublicId(uuid(id)).orElseThrow(this::missing);
    }

    public PublicDemoPage<PublicDemoTaskType> types(MultiValueMap<String, String> params) {
        validateKeys(params, Set.of("page", "size", "sort", "search", "userPublicId"));
        return page(types.visible(uuidParam(params, "userPublicId"), search(params),
                paging(params, Set.of("name"), "name")));
    }

    public PublicDemoTaskType type(String id, MultiValueMap<String, String> params) {
        validateKeys(params, Set.of());
        return types.visibleByPublicId(uuid(id)).orElseThrow(this::missing);
    }

    public PublicDemoPage<PublicDemoTask> tasks(MultiValueMap<String, String> params) {
        validateKeys(params, Set.of("page", "size", "sort", "search", "userPublicId",
                "taskTypePublicId", "completed", "urgency"));
        return page(tasks.visible(uuidParam(params, "userPublicId"), uuidParam(params, "taskTypePublicId"),
                completed(params), urgency(params), search(params),
                paging(params, Set.of("dueDate", "title"), "dueDate")));
    }

    public PublicDemoTask task(String id, MultiValueMap<String, String> params) {
        validateKeys(params, Set.of());
        return tasks.visibleByPublicId(uuid(id)).orElseThrow(this::missing);
    }

    public PublicDemoStats stats(MultiValueMap<String, String> params) {
        validateKeys(params, Set.of());
        return new PublicDemoStats(users.visibleCount(), types.visibleCount(),
                tasks.visibleCount(), tasks.visibleCompletedCount());
    }

    private <T> PublicDemoPage<T> page(Page<T> result) {
        return new PublicDemoPage<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    private PageRequest paging(MultiValueMap<String, String> params, Set<String> allowed, String defaultField) {
        int page = integer(params, "page", 0);
        int size = integer(params, "size", 20);
        if (page < 0 || page > 49 || size < 1 || size > 20) throw bad("page o size fuera de rango");
        String[] parts = value(params, "sort", defaultField + ",asc").split(",", -1);
        if (parts.length > 2 || !allowed.contains(parts[0])
                || (parts.length == 2 && !Set.of("asc", "desc").contains(parts[1].toLowerCase(Locale.ROOT))))
            throw bad("sort no permitido");
        Sort.Direction direction = parts.length == 2 && parts[1].equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, parts[0]).and(Sort.by("publicId")));
    }

    private String search(MultiValueMap<String, String> params) {
        String raw = value(params, "search", null);
        if (raw == null) return null;
        String normalized = raw.trim();
        if (normalized.length() < 2 || normalized.length() > 60) throw bad("search debe tener 2–60 caracteres");
        return normalized.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                .replace("_", "!_");
    }

    private UUID uuidParam(MultiValueMap<String, String> params, String key) {
        String value = value(params, key, null);
        return value == null ? null : uuid(value);
    }

    private UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ex) { throw bad("publicId no válido"); }
    }

    private Boolean completed(MultiValueMap<String, String> params) {
        String value = value(params, "completed", null);
        if (value == null) return null;
        if (!value.equals("true") && !value.equals("false")) throw bad("completed no válido");
        return Boolean.valueOf(value);
    }

    private Integer urgency(MultiValueMap<String, String> params) {
        String value = value(params, "urgency", null);
        if (value == null) return null;
        int number = integer(params, "urgency", -1);
        if (number < 0 || number > 2) throw bad("urgency fuera de rango");
        return number;
    }

    private int integer(MultiValueMap<String, String> params, String key, int defaultValue) {
        String value = value(params, key, null);
        if (value == null) return defaultValue;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw bad(key + " no válido"); }
    }

    private String value(MultiValueMap<String, String> params, String key, String defaultValue) {
        if (!params.containsKey(key)) return defaultValue;
        if (params.get(key).size() != 1) throw bad(key + " repetido");
        return params.getFirst(key);
    }

    private void validateKeys(MultiValueMap<String, String> params, Set<String> allowed) {
        if (!allowed.containsAll(params.keySet())) throw bad("Parámetro no permitido");
    }

    private DemoHttpException bad(String message) { return new DemoHttpException(HttpStatus.BAD_REQUEST, message); }
    private DemoHttpException missing() { return new DemoHttpException(HttpStatus.NOT_FOUND, "Recurso demo no encontrado"); }
}
