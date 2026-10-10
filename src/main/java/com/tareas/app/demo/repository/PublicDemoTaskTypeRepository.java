package com.tareas.app.demo.repository;

import com.tareas.app.demo.dto.publicapi.PublicDemoDtos.PublicDemoTaskType;
import com.tareas.app.demo.model.DemoTaskType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PublicDemoTaskTypeRepository extends Repository<DemoTaskType, Long> {
    default Page<PublicDemoTaskType> visible(UUID userPublicId, String search, Pageable pageable) {
        return search == null ? visibleWithoutSearch(userPublicId, pageable)
                : visibleWithSearch(userPublicId, search, pageable);
    }

    @Query(value = """
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoTaskType(
              t.publicId, u.publicId, t.name, t.description, t.color)
            from DemoTaskType t join DemoUser u on u.id = t.demoUserId
            where t.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and (:userPublicId is null or u.publicId = :userPublicId)
            """, countQuery = """
            select count(t) from DemoTaskType t join DemoUser u on u.id = t.demoUserId
            where t.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and (:userPublicId is null or u.publicId = :userPublicId)
            """)
    Page<PublicDemoTaskType> visibleWithoutSearch(@Param("userPublicId") UUID userPublicId,
                                                  Pageable pageable);

    @Query(value = """
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoTaskType(
              t.publicId, u.publicId, t.name, t.description, t.color)
            from DemoTaskType t join DemoUser u on u.id = t.demoUserId
            where t.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and (:userPublicId is null or u.publicId = :userPublicId)
              and lower(t.name) like concat('%', :search, '%') escape '!'
            """, countQuery = """
            select count(t) from DemoTaskType t join DemoUser u on u.id = t.demoUserId
            where t.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
              and (:userPublicId is null or u.publicId = :userPublicId)
              and lower(t.name) like concat('%', :search, '%') escape '!'
            """)
    Page<PublicDemoTaskType> visibleWithSearch(@Param("userPublicId") UUID userPublicId,
                                               @Param("search") String search, Pageable pageable);

    @Query("""
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoTaskType(
              t.publicId, u.publicId, t.name, t.description, t.color)
            from DemoTaskType t join DemoUser u on u.id = t.demoUserId
            where t.publicId = :publicId and t.publicationStatus = 'PUBLISHED'
              and u.publicationStatus = 'PUBLISHED'
            """)
    Optional<PublicDemoTaskType> visibleByPublicId(@Param("publicId") UUID publicId);

    @Query("""
            select count(t) from DemoTaskType t join DemoUser u on u.id = t.demoUserId
            where t.publicationStatus = 'PUBLISHED' and u.publicationStatus = 'PUBLISHED'
            """)
    long visibleCount();
}
