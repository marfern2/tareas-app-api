package com.tareas.app.demo.repository;

import com.tareas.app.demo.dto.publicapi.PublicDemoDtos.PublicDemoUser;
import com.tareas.app.demo.model.DemoUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PublicDemoUserRepository extends Repository<DemoUser, Long> {
    default Page<PublicDemoUser> visible(String search, Pageable pageable) {
        return search == null ? visibleWithoutSearch(pageable) : visibleWithSearch(search, pageable);
    }

    @Query(value = """
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoUser(
              u.publicId, u.handle, u.displayName, u.bio)
            from DemoUser u where u.publicationStatus = 'PUBLISHED'
            """, countQuery = """
            select count(u) from DemoUser u where u.publicationStatus = 'PUBLISHED'
            """)
    Page<PublicDemoUser> visibleWithoutSearch(Pageable pageable);

    @Query(value = """
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoUser(
              u.publicId, u.handle, u.displayName, u.bio)
            from DemoUser u
            where u.publicationStatus = 'PUBLISHED'
              and (lower(u.handle) like concat('%', :search, '%') escape '!'
                or lower(u.displayName) like concat('%', :search, '%') escape '!')
            """, countQuery = """
            select count(u) from DemoUser u where u.publicationStatus = 'PUBLISHED'
              and (lower(u.handle) like concat('%', :search, '%') escape '!'
                or lower(u.displayName) like concat('%', :search, '%') escape '!')
            """)
    Page<PublicDemoUser> visibleWithSearch(@Param("search") String search, Pageable pageable);

    @Query("""
            select new com.tareas.app.demo.dto.publicapi.PublicDemoDtos$PublicDemoUser(
              u.publicId, u.handle, u.displayName, u.bio)
            from DemoUser u where u.publicId = :publicId and u.publicationStatus = 'PUBLISHED'
            """)
    Optional<PublicDemoUser> visibleByPublicId(@Param("publicId") UUID publicId);

    @Query("select count(u) from DemoUser u where u.publicationStatus = 'PUBLISHED'")
    long visibleCount();
}
