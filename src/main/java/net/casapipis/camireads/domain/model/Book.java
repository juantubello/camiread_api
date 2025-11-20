package net.casapipis.camireads.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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
    private LocalDate startReadDate;

    @Column(name = "end_read_date")
    private LocalDate endReadDate;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "has_url_cover")
    private Boolean hasUrlCover;

    @Column(name = "url_cover")
    private String urlCover;

    @Column(name = "b64_cover", columnDefinition = "text")
    private String b64Cover;

    // --- getters y setters ---

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

    public LocalDate getStartReadDate() {
        return startReadDate;
    }

    public void setStartReadDate(LocalDate startReadDate) {
        this.startReadDate = startReadDate;
    }

    public LocalDate getEndReadDate() {
        return endReadDate;
    }

    public void setEndReadDate(LocalDate endReadDate) {
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
}
