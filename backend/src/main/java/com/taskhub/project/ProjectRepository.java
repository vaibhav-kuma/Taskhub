package com.taskhub.project;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    @Query("""
            SELECT m.project.id, COUNT(m)
            FROM ProjectMember m
            WHERE m.project.id IN :projectIds
            GROUP BY m.project.id
            """)
    List<Object[]> countMembersByProjectIds(@Param("projectIds") Collection<UUID> projectIds);
}
