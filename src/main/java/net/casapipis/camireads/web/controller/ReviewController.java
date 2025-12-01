package net.casapipis.camireads.web.controller;

import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import net.casapipis.camireads.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public List<Review> searchReviews(
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String bookTitle,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String reviewFrom,
            @RequestParam(required = false) String reviewTo,
            @RequestParam(required = false) String readFrom,
            @RequestParam(required = false) String readTo
    ) {

        OffsetDateTime reviewFromDate = parseDate(reviewFrom);
        OffsetDateTime reviewToDate   = parseDate(reviewTo);
        OffsetDateTime readFromDate   = parseDate(readFrom);
        OffsetDateTime readToDate     = parseDate(readTo);

        return reviewService.searchReviews(
                author,
                bookTitle,
                rating,
                reviewFromDate,
                reviewToDate,
                readFromDate,
                readToDate
        );
    }

    /**
     * Intenta parsear:
     *  - primero como OffsetDateTime (ej: 2025-11-01T00:00:00-03:00)
     *  - si falla, como LocalDate (ej: 2025-11-01) usando horario AR (-03:00)
     */
    private OffsetDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // formato ISO completo con offset → 2025-11-01T00:00:00-03:00
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e1) {
            try {
                // solo fecha → 2025-11-01
                LocalDate d = LocalDate.parse(raw);
                ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");
                return d.atStartOfDay(zone).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                // si tampoco matchea LocalDate, ignoramos el filtro
                return null;
            }
        }
    }

    @GetMapping("/latest")
    public Page<Review> getLatestReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return reviewService.getLatestReviews(pageable);
    }

    @GetMapping("/book/{bookId}")
    public ResponseEntity<Review> getReviewByBookId(@PathVariable Long bookId) {
        return reviewService.getReviewByBookId(bookId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/book/{bookId}")
    public ResponseEntity<Review> updateReview(
            @PathVariable Long bookId,
            @RequestBody UpdateReviewRequest request
    ) {
        Review updated = reviewService.updateReview(bookId, request);
        return ResponseEntity.ok(updated);
    }
}
