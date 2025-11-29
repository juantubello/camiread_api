package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // 🔹 Búsqueda combinada: author + bookTitle (la que ya funciona)
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

    // 🔹 Solo por autor (Spring Data arma el JOIN y el LIKE)
    List<Review> findByBook_AuthorContainingIgnoreCase(String author);

    // 🔹 Solo por título
    List<Review> findByBook_TitleContainingIgnoreCase(String bookTitle);
}
