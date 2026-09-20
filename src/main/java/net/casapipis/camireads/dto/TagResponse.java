package net.casapipis.camireads.dto;

import net.casapipis.camireads.domain.model.Tag;

/** Tag tal como lo ve el front: {id, name, slug, color}. */
public record TagResponse(Long id, String name, String slug, String color) {

    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), tag.getSlug(), tag.getColor());
    }
}
