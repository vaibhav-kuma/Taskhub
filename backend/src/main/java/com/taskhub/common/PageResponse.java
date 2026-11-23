package com.taskhub.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Version-stable pagination envelope.
 *
 * <p>Spring's serialized {@code Page} shape is an implementation detail that has changed
 * between releases; this record is the public contract consumed by the JavaFX and web clients.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        String sort,
        String direction) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper, String sort, String direction) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                sort,
                direction);
    }
}
