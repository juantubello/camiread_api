package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.domain.repository.ReviewRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;

    public List<Review> searchReviews(
            String author,
            String bookTitle,
            Integer rating,
            OffsetDateTime reviewFrom,
            OffsetDateTime reviewTo,
            OffsetDateTime readFrom,
            OffsetDateTime readTo
    ) {

        // para debug, si querés ver qué llega:
        System.out.println(">>> author = '" + author + "'");
        System.out.println(">>> bookTitle = '" + bookTitle + "'");

        boolean hasAuthor   = author != null && !author.isBlank();
        boolean hasBookName = bookTitle != null && !bookTitle.isBlank();

        // 🔹 Caso 1: ambos → usamos la nativa que ya funciona
        if (hasAuthor && hasBookName) {
            return reviewRepository.findByAuthorAndBookTitleLike(author, bookTitle);
        }

        // 🔹 Caso 2: solo autor
        if (hasAuthor) {
            return reviewRepository.findByBook_AuthorContainingIgnoreCase(author);
        }

        // 🔹 Caso 3: solo título
        if (hasBookName) {
            return reviewRepository.findByBook_TitleContainingIgnoreCase(bookTitle);
        }

        // 🔹 Caso 4: sin filtros (por ahora devolvemos todo)
        return reviewRepository.findAll();
    }
}
