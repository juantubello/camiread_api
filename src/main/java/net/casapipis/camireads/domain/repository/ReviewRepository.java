package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    // 🔹 Búsqueda combinada: author + bookTitle + rating
    @Query(value = """
        SELECT r.*
        FROM reviews r
        JOIN books b ON b.id = r.book_id
        WHERE LOWER(b.author) LIKE LOWER(CONCAT('%', :author, '%'))
          AND LOWER(b.title)  LIKE LOWER(CONCAT('%', :bookTitle, '%'))
          AND r.rating = :rating
        ORDER BY r.created_at DESC
        """,
            nativeQuery = true)
    List<Review> findByAuthorAndBookTitleAndRating(
            @Param("author") String author,
            @Param("bookTitle") String bookTitle,
            @Param("rating") int rating
    );

    // 🔹 Solo autor
    List<Review> findByBook_AuthorContainingIgnoreCase(String author);

    // 🔹 Solo título
    List<Review> findByBook_TitleContainingIgnoreCase(String bookTitle);

    // 🔹 Solo rating
    List<Review> findByRating(int rating);

    // 🔹 autor + rating
    List<Review> findByBook_AuthorContainingIgnoreCaseAndRating(String author, int rating);

    // 🔹 título + rating
    List<Review> findByBook_TitleContainingIgnoreCaseAndRating(String bookTitle, int rating);

    // 🔹 Últimas reseñas ordenadas por fecha DESC, paginadas
    Page<Review> findAllByOrderByCreatedAtDesc(Pageable pageable);

}
