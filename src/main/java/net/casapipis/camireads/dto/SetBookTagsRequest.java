package net.casapipis.camireads.dto;

import lombok.Data;

import java.util.List;

/**
 * Body de PUT /reviews/book/{bookId}/tags.
 * Reemplaza el conjunto COMPLETO de tags del libro:
 *  - tagIds:       tags que ya existen
 *  - newTagNames:  nombres libres; se crean, o se reusan si el slug ya existe
 * Mandar los dos vacios deja el libro sin tags.
 */
@Data
public class SetBookTagsRequest {
    private List<Long> tagIds;
    private List<String> newTagNames;
}
