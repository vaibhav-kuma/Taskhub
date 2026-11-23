package com.taskhub.desktop.config;

import com.taskhub.desktop.api.ProjectApi;
import com.taskhub.desktop.api.TaskApi;
import com.taskhub.desktop.auth.SessionManager;
import com.taskhub.desktop.api.ApiClient;

/**
 * Tiny manual dependency container. Explicit wiring keeps the UI classes free of static state and
 * makes swapping the base URL (re-login against another server) a simple object graph rebuild.
 */
public final class AppContext {

    private final String baseUrl;
    private final ApiClient authClient;
    private final ApiClient apiClient;
    private final SessionManager session;
    private final ProjectApi projects;
    private final TaskApi tasks;

    public AppContext(String baseUrl) {
        this.baseUrl = baseUrl;
        this.authClient = new ApiClient(baseUrl);
        this.apiClient = new ApiClient(baseUrl);
        this.session = new SessionManager(authClient, apiClient);
        this.projects = new ProjectApi(apiClient);
        this.tasks = new TaskApi(apiClient);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public SessionManager session() {
        return session;
    }

    public ProjectApi projects() {
        return projects;
    }

    public TaskApi tasks() {
        return tasks;
    }

    public ApiClient apiClient() {
        return apiClient;
    }
}
