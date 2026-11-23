package com.taskhub.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Lightweight project reference embedded in a task response. */
@Schema(name = "ProjectSummary")
public record ProjectSummary(UUID id, String code, String name) {

    public static ProjectSummary from(Project project) {
        return new ProjectSummary(project.getId(), project.getCode(), project.getName());
    }
}
