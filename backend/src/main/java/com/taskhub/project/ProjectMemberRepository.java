package com.taskhub.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findAllByProjectIdOrderByAddedAtAsc(UUID projectId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    @Modifying
    @Query("DELETE FROM ProjectMember m WHERE m.project.id = :projectId AND m.user.id = :userId")
    int deleteMembership(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    long countByProjectId(UUID projectId);
}
