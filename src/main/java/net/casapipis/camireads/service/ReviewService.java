package net.casapipis.camireads.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.model.Book;
import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.domain.model.ReviewQuote;
import net.casapipis.camireads.domain.repository.BookRepository;
import net.casapipis.camireads.domain.repository.ReviewRepository;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookRepository bookRepository;

    @Transactional
    public Review updateReview(Long bookId, UpdateReviewRequest request) {

        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new EntityNotFoundException("Book not found: " + bookId));

        Review review = reviewRepository.findTopByBook_IdOrderByCreatedAtDesc(bookId)
                .orElseThrow(() -> new EntityNotFoundException("Review not found for book: " + bookId));

        // Fechas de lectura del libro
        if (request.getStartReadDate() != null) {
            book.setStartReadDate(request.getStartReadDate());
        }
        if (request.getEndReadDate() != null) {
            book.setEndReadDate(request.getEndReadDate());
        }

        // Portada
        if (request.getUrlCover() != null) {
            String cover = request.getUrlCover().isBlank() ? null : request.getUrlCover();
            book.setUrlCover(cover);
            book.setHasUrlCover(cover != null);
        }

        bookRepository.save(book);

        // Datos de la review
        if (request.getRating() != null) {
            review.setRating(request.getRating());
        }
        if (request.getReviewText() != null) {
            review.setReviewText(request.getReviewText());
        }

        // Quotes: borramos todas y volvemos a crear
        review.getQuotes().clear();

        if (request.getQuotes() != null) {
            for (String q : request.getQuotes()) {
                if (q == null || q.isBlank()) continue;

                ReviewQuote rq = new ReviewQuote();
                rq.setReview(review);
                rq.setQuoteText(q);
                rq.setCreatedAt(OffsetDateTime.now());

                review.getQuotes().add(rq);
            }
        }

        return reviewRepository.save(review);
    }

    public Page<Review> getLatestReviews(Pageable pageable) {
        return reviewRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Optional<Review> getReviewByBookId(Long bookId) {
        return reviewRepository.findTopByBook_IdOrderByCreatedAtDesc(bookId);
    }

    public List<Review> getReviewsByBookId(Long bookId) {
        return reviewRepository.findByBook_IdOrderByCreatedAtDesc(bookId);
    }

    public List<Review> searchReviews(
            String author,
            String bookTitle,
            Integer rating,
            OffsetDateTime reviewFrom,
            OffsetDateTime reviewTo,
            OffsetDateTime readFrom,
            OffsetDateTime readTo
    ) {

        boolean hasAuthor   = author != null && !author.isBlank();
        boolean hasBookName = bookTitle != null && !bookTitle.isBlank();
        boolean hasRating   = rating != null;

        // 1) Filtro base por autor / título / rating (igual que antes)
        List<Review> base;

        if (hasAuthor && hasBookName && hasRating) {
            base = reviewRepository.findByAuthorAndBookTitleAndRating(author, bookTitle, rating);
        } else if (hasAuthor && hasRating) {
            base = reviewRepository.findByBook_AuthorContainingIgnoreCaseAndRating(author, rating);
        } else if (hasBookName && hasRating) {
            base = reviewRepository.findByBook_TitleContainingIgnoreCaseAndRating(bookTitle, rating);
        } else if (hasRating) {
            base = reviewRepository.findByRating(rating);
        } else if (hasAuthor) {
            base = reviewRepository.findByBook_AuthorContainingIgnoreCase(author);
        } else if (hasBookName) {
            base = reviewRepository.findByBook_TitleContainingIgnoreCase(bookTitle);
        } else {
            base = reviewRepository.findAll();
        }

        // 2) Filtro por fechas de lectura (start/end del BOOK)
        if (readFrom == null && readTo == null) {
            return base; // sin filtro de fechas
        }

        return base.stream()
                .filter(review -> {
                    OffsetDateTime start = review.getBook().getStartReadDate();
                    OffsetDateTime end   = review.getBook().getEndReadDate();

                    // --- Caso A: SOLO readFrom ---
                    if (readFrom != null && readTo == null) {
                        // Queremos: startReadDate >= readFrom
                        if (start == null) return false;
                        return !start.isBefore(readFrom); // start >= readFrom
                    }

                    // --- Caso B: SOLO readTo ---
                    if (readFrom == null && readTo != null) {
                        // Queremos: endReadDate <= readTo
                        // Si no tenemos fecha de fin, no sabemos si terminó → lo excluimos
                        if (end == null) return false;
                        return !end.isAfter(readTo); // end <= readTo
                    }

                    // --- Caso C: readFrom Y readTo ---
                    if (readFrom != null && readTo != null) {
                        // Queremos libro COMPLETAMENTE entre el rango:
                        // start >= readFrom  AND  end <= readTo
                        if (start == null || end == null) return false;
                        if (start.isBefore(readFrom)) return false; // start < readFrom → afuera
                        if (end.isAfter(readTo)) return false;      // end > readTo → afuera
                        return true;
                    }

                    // fallback (no deberíamos llegar acá)
                    return true;
                })
                .collect(Collectors.toList());
    }
}
