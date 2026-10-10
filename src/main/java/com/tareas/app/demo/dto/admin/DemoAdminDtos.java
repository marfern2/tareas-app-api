package com.tareas.app.demo.dto.admin;

import com.tareas.app.demo.model.PublicationStatus;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Private admin wire contract. IDs here are internal demo IDs; fixture keys are never exposed. */
public final class DemoAdminDtos {
    private DemoAdminDtos() {}

    public record UserCreate(@NotBlank String handle, @NotBlank String displayName, String bio) {}
    public record UserPatch(String handle, String displayName, String bio) {}
    public record UserView(Long id, UUID publicId, String handle, String displayName, String bio,
                           PublicationStatus publicationStatus, Instant publishedAt, long version,
                           Instant createdAt, Instant updatedAt) {}

    public record TypeCreate(@NotNull Long demoUserId, @NotBlank String name, String description,
                             @NotBlank String color) {}
    public record TypePatch(Long demoUserId, String name, String description, String color) {}
    public record TypeView(Long id, UUID publicId, Long demoUserId, String name, String description,
                           String color, PublicationStatus publicationStatus, Instant publishedAt,
                           long version, Instant createdAt, Instant updatedAt) {}

    public record TaskCreate(@NotNull Long demoUserId, @NotNull Long demoTaskTypeId,
                             @NotBlank String title, String description, @NotNull LocalDate dueDate,
                             @NotNull Boolean completed, @NotNull Integer urgency) {}
    public record TaskPatch(Long demoUserId, Long demoTaskTypeId, String title, String description,
                            LocalDate dueDate, Boolean completed, Integer urgency) {}
    public record TaskView(Long id, UUID publicId, Long demoUserId, Long demoTaskTypeId,
                           String title, String description, LocalDate dueDate, boolean completed,
                           int urgency, PublicationStatus publicationStatus, Instant publishedAt,
                           long version, Instant createdAt, Instant updatedAt) {}

    public record Publication(@NotNull PublicationStatus publicationStatus) {}
    public record Stats(long usersTotal, long usersPublished, long typesTotal, long typesPublished,
                        long tasksTotal, long tasksPublished, long tasksCompleted) {}
}
