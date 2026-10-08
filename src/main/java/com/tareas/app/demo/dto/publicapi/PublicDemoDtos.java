package com.tareas.app.demo.dto.publicapi;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PublicDemoDtos {
    private PublicDemoDtos() {}

    public record PublicDemoUser(UUID publicId, String handle, String displayName, String bio) {}
    public record PublicDemoTaskType(UUID publicId, UUID userPublicId, String name,
                                     String description, String color) {}
    public record PublicDemoTask(UUID publicId, UUID userPublicId, UUID taskTypePublicId,
                                 String title, String description, LocalDate dueDate,
                                 boolean completed, int urgency) {}
    public record PublicDemoStats(long users, long taskTypes, long tasks, long completedTasks) {}
    public record PublicDemoPage<T>(List<T> content, int page, int size, long totalElements,
                                    int totalPages, boolean hasNext) {}
}
