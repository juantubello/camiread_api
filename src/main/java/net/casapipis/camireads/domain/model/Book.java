package net.casapipis.camireads.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "books")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String author;

    @Column(name = "start_read_date")
    private OffsetDateTime startReadDate;

    @Column(name = "end_read_date")
    private OffsetDateTime endReadDate;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "has_url_cover")
    private Boolean hasUrlCover;

    @Column(name = "url_cover")
    private String urlCover;

    @Column(name = "b64_cover")
    private String b64Cover;

    /**
     * Tags del libro (los tags cuelgan del LIBRO, no de la resenia).
     *
     * Lado duenio de la relacion: la tabla puente book_tags. La columna
     * created_at de book_tags la pone la base con su DEFAULT now(), por eso
     * aca solo mapeamos las dos FKs.
     *
     * Campo ADITIVO en el JSON: aparece como "tags": [{id,name,slug,color}].
     * Ningun campo existente cambia de nombre ni desaparece.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "book_tags",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @OrderBy("name ASC")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Set<Tag> tags = new LinkedHashSet<>();

    // --- Getters y Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public OffsetDateTime getStartReadDate() {
        return startReadDate;
    }

    public void setStartReadDate(OffsetDateTime startReadDate) {
        this.startReadDate = startReadDate;
    }

    public OffsetDateTime getEndReadDate() {
        return endReadDate;
    }

    public void setEndReadDate(OffsetDateTime endReadDate) {
        this.endReadDate = endReadDate;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getHasUrlCover() {
        return hasUrlCover;
    }

    public void setHasUrlCover(Boolean hasUrlCover) {
        this.hasUrlCover = hasUrlCover;
    }

    public String getUrlCover() {
        return urlCover;
    }

    public void setUrlCover(String urlCover) {
        this.urlCover = urlCover;
    }

    public String getB64Cover() {
        return b64Cover;
    }

    public void setB64Cover(String b64Cover) {
        this.b64Cover = b64Cover;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public void setTags(Set<Tag> tags) {
        this.tags = tags;
    }
}
