package com.taskhub.desktop.api;

/**
 * Supplies the current access token and can renew it. Implemented by the session manager; kept as
 * an interface so the HTTP client has no dependency on UI or session state.
 */
public interface TokenProvider {

    /** @return the current access token, or {@code null} when signed out. */
    String accessToken();

    /**
     * Attempts to obtain a new access token.
     *
     * @return {@code true} when a retry with the new token is worthwhile
     */
    boolean refresh();
}
