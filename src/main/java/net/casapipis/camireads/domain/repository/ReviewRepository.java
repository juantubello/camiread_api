package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.util.Optional;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // 🔹 Búsqueda combinada: author + bookTitle (YA FUNCIONABA)
    @Query(value = """
        SELECT r.*
        FROM reviews r
        JOIN books b ON b.id = r.book_id
        WHERE LOWER(b.author) LIKE LOWER(CONCAT('%', :author, '%'))
          AND LOWER(b.title)  LIKE LOWER(CONCAT('%', :bookTitle, '%'))
        ORDER BY r.created_at DESC
        """,
            nativeQuery = true)
    List<Review> findByAuthorAndBookTitleLike(
            @Param("author") String author,
            @Param("bookTitle") String bookTitle
    );

    // ─────────────────────────────────────────────────────────────
    // Filtro por puntaje (Fase 9, cuartos de estrella)
    // ─────────────────────────────────────────────────────────────
    //
    // Desde que rating es numeric(3,2), el filtro "N estrellas" ya no puede
    // ser `rating = N`: un 3.75 no apareceria NUNCA en ninguna busqueda. Ahora
    // es un rango [min, max), o igualdad exacta cuando exact = true (el 0 de
    // "sin calificar" y el 5). El rango lo arma ReviewService.ratingRange(),
    // en UN solo lugar para todos los caminos de busqueda.
    //
    // Con exact = true se pasa max = min, asi la segunda mitad del OR queda
    // vacia y solo matchea la igualdad.

    // 🔹 Búsqueda combinada: author + bookTitle + rango de rating
    @Query(value = """
        SELECT r.*
        FROM reviews r
        JOIN books b ON b.id = r.book_id
        WHERE LOWER(b.author) LIKE LOWER(CONCAT('%', :author, '%'))
          AND LOWER(b.title)  LIKE LOWER(CONCAT('%', :bookTitle, '%'))
          AND ((:exact AND r.rating = :min)
               OR (r.rating >= :min AND r.rating < :max))
        ORDER BY r.created_at DESC
        """,
            nativeQuery = true)
    List<Review> findByAuthorAndBookTitleAndRatingRange(
            @Param("author") String author,
            @Param("bookTitle") String bookTitle,
            @Param("min") BigDecimal min,
            @Param("max") BigDecimal max,
            @Param("exact") boolean exact
    );

    // 🔹 Solo rango de rating
    @Query("""
        select r from Review r
        where (:exact = true and r.rating = :min)
           or (r.rating >= :min and r.rating < :max)
        """)
    List<Review> findByRatingRange(
            @Param("min") BigDecimal min,
            @Param("max") BigDecimal max,
            @Param("exact") boolean exact
    );

    // 🔹 autor + rango de rating
    @Query("""
        select r from Review r join r.book b
        where lower(b.author) like lower(concat('%', :author, '%'))
          and ((:exact = true and r.rating = :min)
               or (r.rating >= :min and r.rating < :max))
        """)
    List<Review> findByAuthorAndRatingRange(
            @Param("author") String author,
            @Param("min") BigDecimal min,
            @Param("max") BigDecimal max,
            @Param("exact") boolean exact
    );

    // 🔹 título + rango de rating
    @Query("""
        select r from Review r join r.book b
        where lower(b.title) like lower(concat('%', :bookTitle, '%'))
          and ((:exact = true and r.rating = :min)
               or (r.rating >= :min and r.rating < :max))
        """)
    List<Review> findByTitleAndRatingRange(
            @Param("bookTitle") String bookTitle,
            @Param("min") BigDecimal min,
            @Param("max") BigDecimal max,
            @Param("exact") boolean exact
    );

    // 🔹 Solo autor
    List<Review> findByBook_AuthorContainingIgnoreCase(String author);

    // 🔹 Solo título
    List<Review> findByBook_TitleContainingIgnoreCase(String bookTitle);

    // 🔹 Últimas reseñas ordenadas por fecha DESC, paginadas
    Page<Review> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // última reseña (por si en el futuro hay más de una por libro)
    Optional<Review> findTopByBook_IdOrderByCreatedAtDesc(Long bookId);

    // si querés todas las reseñas de ese libro:
    List<Review> findByBook_IdOrderByCreatedAtDesc(Long bookId);

    // ─────────────────────────────────────────────────────────────
    // Tags / anti N+1
    // ─────────────────────────────────────────────────────────────

    /**
     * Misma pagina que findAllByOrderByCreatedAtDesc pero trayendo el libro
     * en el mismo SELECT (JOIN FETCH sobre un @ManyToOne: es seguro paginar).
     */
    @Query(value = "select r from Review r join fetch r.book",
           countQuery = "select count(r) from Review r")
    Page<Review> findAllWithBook(Pageable pageable);

    /**
     * Segunda query del patron "dos queries": inicializa las quotes de un lote
     * de reviews que ya estan en el persistence context. El resultado se ignora
     * a proposito; lo que importa es el efecto sobre la sesion.
     */
    @Query("select distinct r from Review r left join fetch r.quotes where r.id in :ids")
    List<Review> fetchQuotesForReviews(@Param("ids") java.util.Collection<Long> ids);

    /** Reviews de un conjunto de libros (se usa para el filtro por tags). */
    List<Review> findByBook_IdInOrderByCreatedAtDesc(java.util.Collection<Long> bookIds);
}
