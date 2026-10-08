package com.tareas.app.demo.repository;

import com.tareas.app.demo.dto.publicapi.PublicDemoDtos.PublicDemoTask;
import com.tareas.app.demo.model.DemoTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PublicDemoTaskRepository extends Repository<DemoTask, Long> {
    @Query(value = """
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoTask(
              k.publicId, u.publicId, t.publicId, k.title, k.description,
              k.dueDate, k.completed, k.urgency)
            from DemoTask k join DemoUser u on u.id = k.demoUserId
              join DemoTaskType t on t.id = k.demoTaskTypeId and t.demoUserId = u.id
            where k.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and t.publicationStatus = 'PUBLISHED'
              and (:userPublicId is null or u.publicId = :userPublicId)
              and (:taskTypePublicId is null or t.publicId = :taskTypePublicId)
              and (:completed is null or k.completed = :completed)
              and (:urgency is null or k.urgency = :urgency)
              and (:search is null or lower(k.title) like concat('%', :search, '%') escape '!')
            """, countQuery = """
            select count(k) from DemoTask k join DemoUser u on u.id = k.demoUserId
              join DemoTaskType t on t.id = k.demoTaskTypeId and t.demoUserId = u.id
            where k.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and t.publicationStatus = 'PUBLISHED'
              and (:userPublicId is null or u.publicId = :userPublicId)
              and (:taskTypePublicId is null or t.publicId = :taskTypePublicId)
              and (:completed is null or k.completed = :completed)
              and (:urgency is null or k.urgency = :urgency)
              and (:search is null or lower(k.title) like concat('%', :search, '%') escape '!')
            """)
    Page<PublicDemoTask> visible(@Param("userPublicId") UUID userPublicId,
                                 @Param("taskTypePublicId") UUID taskTypePublicId,
                                 @Param("completed") Boolean completed,
                                 @Param("urgency") Integer urgency,
                                 @Param("search") String search, Pageable pageable);

    @Query("""
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoTask(
              k.publicId, u.publicId, t.publicId, k.title, k.description,
              k.dueDate, k.completed, k.urgency)
            from DemoTask k join DemoUser u on u.id = k.demoUserId
              join DemoTaskType t on t.id = k.demoTaskTypeId and t.demoUserId = u.id
            where k.publicId = :publicId and k.publicationStatus = 'PUBLISHED'
              and u.publicationStatus = 'PUBLISHED' and t.publicationStatus = 'PUBLISHED'
            """)
    Optional<PublicDemoTask> visibleByPublicId(@Param("publicId") UUID publicId);

    @Query("""
            select count(k) from DemoTask k join DemoUser u on u.id = k.demoUserId
              join DemoTaskType t on t.id = k.demoTaskTypeId and t.demoUserId = u.id
            where k.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and t.publicationStatus = 'PUBLISHED'
            """)
    long visibleCount();

    @Query("""
            select count(k) from DemoTask k join DemoUser u on u.id = k.demoUserId
              join DemoTaskType t on t.id = k.demoTaskTypeId and t.demoUserId = u.id
            where k.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and t.publicationStatus = 'PUBLISHED' and k.completed = true
            """)
    long visibleCompletedCount();
}
