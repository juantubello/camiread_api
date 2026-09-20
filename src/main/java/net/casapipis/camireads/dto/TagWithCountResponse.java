package net.casapipis.camireads.dto;

/** Item de GET /tags: el tag + cuantos libros lo tienen. */
public record TagWithCountResponse(Long id, String name, String slug, String color, long bookCount) {
}
