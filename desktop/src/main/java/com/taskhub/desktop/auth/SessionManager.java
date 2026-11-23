package com.taskhub.desktop.auth;

import com.taskhub.desktop.api.ApiClient;
import com.taskhub.desktop.api.ApiException;
import com.taskhub.desktop.api.AuthApi;
import com.taskhub.desktop.api.Dtos;
import com.taskhub.desktop.api.Requests;
import com.taskhub.desktop.api.TokenProvider;

/**
 * Owns the authenticated session.
 *
 * <p>Refresh is single-flight: the five concurrent loads the UI issues on start-up trigger at most
 * one refresh call. A refresh that fails clears the session, which sends the UI back to login.
 */
public class SessionManager implements TokenProvider {

    private final ApiClient authClient;
    private final ApiClient apiClient;
    private final AuthApi authApi;
    private final Object refreshLock = new Object();

    private volatile Dtos.Auth session;

    public SessionManager(ApiClient authClient, ApiClient apiClient) {
        this.authClient = authClient;
        this.apiClient = apiClient;
        this.authApi = new AuthApi(authClient);
        this.apiClient.setTokenProvider(this);
    }

    public Dtos.User login(String email, String password) {
        Dtos.Auth auth = authApi.login(email, password);
        apply(auth);
        return auth.user();
    }

    public Dtos.User register(String email, String password, String displayName) {
        Dtos.Auth auth = authApi.register(email, password, displayName);
        apply(auth);
        return auth.user();
    }

    public void logout() {
        Dtos.Auth current = session;
        if (current != null) {
            try {
                apiClient.postNoContent("/api/v1/auth/logout",
                        new Requests.Refresh(current.refreshToken()));
            } catch (ApiException ignored) {
                // Best effort: local session is dropped regardless.
            }
        }
        clear();
    }

    public Dtos.User user() {
        Dtos.Auth current = session;
        return current == null ? null : current.user();
    }

    public boolean isAuthenticated() {
        return session != null;
    }

    public String refreshToken() {
        Dtos.Auth current = session;
        return current == null ? null : current.refreshToken();
    }

    @Override
    public String accessToken() {
        Dtos.Auth current = session;
        return current == null ? null : current.accessToken();
    }

    @Override
    public boolean refresh() {
        Dtos.Auth current = session;
        if (current == null) {
            return false;
        }
        synchronized (refreshLock) {
            Dtos.Auth latest = session;
            if (latest == null) {
                return false;
            }
            if (!latest.accessToken().equals(current.accessToken())) {
                return true; // another thread already refreshed while we waited
            }
            try {
                Dtos.Auth renewed = authApi.refresh(latest.refreshToken());
                apply(renewed);
                return true;
            } catch (ApiException ex) {
                clear();
                return false;
            }
        }
    }

    private void apply(Dtos.Auth auth) {
        this.session = auth;
        apiClient.setAccessToken(auth.accessToken());
    }

    private void clear() {
        this.session = null;
        apiClient.setAccessToken(null);
    }
}
