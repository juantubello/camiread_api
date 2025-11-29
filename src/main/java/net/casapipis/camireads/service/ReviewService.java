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
            Integer rating,              // viene como Integer desde el controller
            OffsetDateTime reviewFrom,   // por ahora sin usar
            OffsetDateTime reviewTo,
            OffsetDateTime readFrom,
            OffsetDateTime readTo
    ) {

        boolean hasAuthor   = author != null && !author.isBlank();
        boolean hasBookName = bookTitle != null && !bookTitle.isBlank();
        boolean hasRating   = rating != null;

        if (hasAuthor && hasBookName && hasRating) {
            return reviewRepository.findByAuthorAndBookTitleAndRating(
                    author,
                    bookTitle,
                    rating
            );
        }

        if (hasAuthor && hasRating) {
            return reviewRepository.findByBook_AuthorContainingIgnoreCaseAndRating(
                    author,
                    rating
            );
        }

        if (hasBookName && hasRating) {
            return reviewRepository.findByBook_TitleContainingIgnoreCaseAndRating(
                    bookTitle,
                    rating
            );
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
