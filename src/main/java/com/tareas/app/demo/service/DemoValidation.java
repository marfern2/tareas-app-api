package com.tareas.app.demo.service;

import com.tareas.app.demo.model.PublicationStatus;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class DemoValidation {
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9][a-z0-9_-]{2,39}");
    private static final Pattern COLOR = Pattern.compile("#[A-Fa-f0-9]{6}");
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)[<>]|https?://|www\\.|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?::\\d+)?(?:/\\S*)?|&(?:#\\d+|[a-z]+);|javascript:");

    public String handle(String value) {
        String normalized = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
        if (!HANDLE.matcher(normalized).matches()) throw bad("handle debe tener 3–40 caracteres ASCII válidos");
        return normalized;
    }

    public String text(String value, String field, int min, int max) {
        if (value == null) {
            if (min > 0) throw bad(field + " es obligatorio");
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() < min || normalized.length() > max || FORBIDDEN.matcher(normalized).find()
                || normalized.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw bad(field + " contiene texto no válido");
        }
        return normalized;
    }

    public String color(String value) {
        if (value == null || !COLOR.matcher(value).matches()) throw bad("color debe ser #RRGGBB");
        return value.toUpperCase(Locale.ROOT);
    }

    public int urgency(Integer value) {
        if (value == null || value < 0 || value > 2) throw bad("urgency debe estar entre 0 y 2");
        return value;
    }

    public PageRequest page(int page, int size, String sort, Set<String> allowed) {
        if (page < 0 || size < 1 || size > 100) throw bad("page o size fuera de rango");
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !allowed.contains(parts[0]) ||
                (parts.length == 2 && !Set.of("asc", "desc").contains(parts[1].toLowerCase(Locale.ROOT))))
            throw bad("sort no permitido");
        Sort.Direction direction = parts.length == 2 && parts[1].equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort ordering = Sort.by(direction, parts[0]);
        if (!parts[0].equals("id")) ordering = ordering.and(Sort.by("id"));
        return PageRequest.of(page, size, ordering);
    }

    public long ifMatch(String value) {
        if (value == null) throw new DemoHttpException(HttpStatus.PRECONDITION_REQUIRED, "If-Match es obligatorio");
        if (!value.matches("\"v(?:0|[1-9][0-9]*)\"")) throw bad("If-Match debe tener formato \"vN\"");
        try { return Long.parseLong(value.substring(2, value.length() - 1)); }
        catch (NumberFormatException ex) { throw bad("If-Match fuera de rango"); }
    }

    public void version(long expected, Long actual) {
        if (actual == null || expected != actual)
            throw new DemoHttpException(HttpStatus.PRECONDITION_FAILED, "Versión obsoleta");
    }

    public void status(PublicationStatus status) {
        if (status == null) throw bad("publicationStatus es obligatorio");
    }

    public DemoHttpException bad(String message) { return new DemoHttpException(HttpStatus.BAD_REQUEST, message); }
    public DemoHttpException conflict(String message) { return new DemoHttpException(HttpStatus.CONFLICT, message); }
    public DemoHttpException missing() { return new DemoHttpException(HttpStatus.NOT_FOUND, "Recurso demo no encontrado"); }
}
