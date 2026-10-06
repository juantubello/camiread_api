package net.casapipis.camireads.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "reviews")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK a books.book_id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    // numeric(3,2) en la base (Fase 9, cuartos de estrella): 0 = "sin
    // calificar", calificado = 0.25..5 en pasos de 0.25 (lo garantiza un CHECK).
    // BigDecimal y no double: 3.25 tiene que ir y volver EXACTO, y la
    // comparacion contra el CHECK (rating * 4 = trunc(rating * 4)) no perdona
    // un 3.2499999.
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "review_text", columnDefinition = "text")
    private String reviewText;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    // @OrderBy: antes las frases salían en el orden físico de la tabla (arbitrario,
    // cambia solo con un VACUUM). Ahora salen siempre por id ascendente = orden en
    // que se cargaron. Hace falta para que el orden no dependa de si la colección
    // se trae con JOIN FETCH en lote o lazy de a una.
    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @JsonIgnoreProperties({"review", "hibernateLazyInitializer", "handler"})
    private List<ReviewQuote> quotes = new ArrayList<>();

    // --- getters y setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public String getReviewText() {
        return reviewText;
    }

    public void setReviewText(String reviewText) {
        this.reviewText = reviewText;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<ReviewQuote> getQuotes() {
        return quotes;
    }

    public void setQuotes(List<ReviewQuote> quotes) {
        this.quotes = quotes;
    }

}
