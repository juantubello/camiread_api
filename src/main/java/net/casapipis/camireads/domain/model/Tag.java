package net.casapipis.camireads.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Tag (etiqueta) que cuelga del LIBRO, no de la resenia.
 *
 * El slug lo normaliza la aplicacion (ver {@link net.casapipis.camireads.service.TagSlugNormalizer}),
 * NO la base: la base solo garantiza unicidad con el indice ux_tags_slug.
 *
 * Serializacion JSON: {id, name, slug, color}. created_at queda fuera del payload
 * a proposito (dato interno, el front no lo usa).
 */
@Entity
@Table(name = "tags")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "slug", nullable = false, length = 60, unique = true)
    private String slug;

    @Column(name = "color", length = 24)
    private String color;

    // La base tiene DEFAULT now(); lo dejamos como generado para no pisarlo nunca.
    @Column(name = "created_at", insertable = false, updatable = false)
    @JsonIgnore
    private OffsetDateTime createdAt;

    // --- Getters y Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // equals/hashCode por id: los Tag viven adentro de un Set en Book.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tag)) return false;
        Tag other = (Tag) o;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
