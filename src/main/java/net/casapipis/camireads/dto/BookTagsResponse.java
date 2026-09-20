package net.casapipis.camireads.dto;

import java.util.List;

/** Respuesta de PUT /reviews/book/{bookId}/tags: como quedo el libro. */
public record BookTagsResponse(Long bookId, List<TagResponse> tags) {
}
