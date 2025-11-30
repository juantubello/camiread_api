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

        if (request.getStartReadDate() != null) {
            book.setStartReadDate(request.getStartReadDate());
        }
        if (request.getEndReadDate() != null) {
            book.setEndReadDate(request.getEndReadDate());
        }

        if (request.getUrlCover() != null) {
            String cover = request.getUrlCover().isBlank() ? null : request.getUrlCover();
            book.setUrlCover(cover);
            book.setHasUrlCover(cover != null);
        }

        bookRepository.save(book);

        if (request.getRating() != null) {
            review.setRating(request.getRating());
        }
        if (request.getReviewText() != null) {
            review.setReviewText(request.getReviewText());
        }

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

        boolean hasAuthor = author != null && !author.isBlank();
        boolean hasBookName = bookTitle != null && !bookTitle.isBlank();
        boolean hasRating = rating != null;

        if (hasAuthor && hasBookName && hasRating) {
            return reviewRepository.findByAuthorAndBookTitleAndRating(author, bookTitle, rating);
        }

        if (hasAuthor && hasRating) {
            return reviewRepository.findByBook_AuthorContainingIgnoreCaseAndRating(author, rating);
        }

        if (hasBookName && hasRating) {
            return reviewRepository.findByBook_TitleContainingIgnoreCaseAndRating(bookTitle, rating);
        }

        if (hasRating) {
            return reviewRepository.findByRating(rating);
        }

        if (hasAuthor) {
            return reviewRepository.findByBook_AuthorContainingIgnoreCase(author);
        }

        if (hasBookName) {
            return reviewRepository.findByBook_TitleContainingIgnoreCase(bookTitle);
        }

        return reviewRepository.findAll();
    }
}
