package com.taskhub.desktop.api;


public class AuthApi {

    private final ApiClient client;

    public AuthApi(ApiClient client) {
        this.client = client;
    }

    public Dtos.Auth login(String email, String password) {
        return client.post("/api/v1/auth/login", new Requests.Login(email, password), Dtos.Auth.class);
    }

    public Dtos.Auth register(String email, String password, String displayName) {
        return client.post("/api/v1/auth/register",
                new Requests.Register(email, password, displayName), Dtos.Auth.class);
    }

    public Dtos.Auth refresh(String refreshToken) {
        return client.post("/api/v1/auth/refresh", new Requests.Refresh(refreshToken), Dtos.Auth.class);
    }

    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null) {
            client.setAccessToken(accessToken);
        }
        client.postNoContent("/api/v1/auth/logout", new Requests.Refresh(refreshToken));
    }

    public Dtos.User me() {
        return client.get("/api/v1/auth/me", Dtos.User.class);
    }
}
