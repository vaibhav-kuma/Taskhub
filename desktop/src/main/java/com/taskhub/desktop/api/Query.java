package com.taskhub.desktop.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Small query-string builder that drops null/blank values and percent-encodes everything.
 * Keeps URL construction in one place instead of string concatenation scattered across services.
 */
public final class Query {

    private final Map<String, String> params = new LinkedHashMap<>();

    private Query() {
    }

    public static Query create() {
        return new Query();
    }

    public Query add(String name, Object value) {
        if (value == null) {
            return this;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return this;
        }
        params.put(name, text);
        return this;
    }

    public Query page(int page, int size, String sort, String direction) {
        return add("page", page).add("size", size).add("sort", sort).add("direction", direction);
    }

    @Override
    public String toString() {
        if (params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&", "?", ""));
    }
}
