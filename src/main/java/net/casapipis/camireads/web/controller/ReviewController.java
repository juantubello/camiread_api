package net.casapipis.camireads.web.controller;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.dto.BookTagsResponse;
import net.casapipis.camireads.dto.NewReviewRequest;
import net.casapipis.camireads.dto.SetBookTagsRequest;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import net.casapipis.camireads.service.ReviewService;
import net.casapipis.camireads.service.TagService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)
@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final TagService tagService;

    @GetMapping
    public List<Review> searchReviews(
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String bookTitle,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String reviewFrom,
            @RequestParam(required = false) String reviewTo,
            @RequestParam(required = false) String readFrom,
            @RequestParam(required = false) String readTo,
            // 🔹 NUEVO (aditivo): filtro por tags, combinable con todo lo de arriba.
            //    tagIds acepta "?tagIds=11,12" y también "?tagIds=11&tagIds=12".
            //    tagMode: "any" (default) = alguno de esos tags | "all" = todos.
            @RequestParam(required = false) List<String> tagIds,
            @RequestParam(required = false, defaultValue = "any") String tagMode
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
                readToDate,
                parseTagIds(tagIds),
                "all".equalsIgnoreCase(tagMode == null ? "" : tagMode.trim())
        );
    }

    /**
     * PUT /reviews/book/{bookId}/tags
     * Reemplaza el conjunto de tags del libro. Los newTagNames se crean o se
     * reusan si el slug ya existe.
     */
    @PutMapping("/book/{bookId}/tags")
    public BookTagsResponse setBookTags(
            @PathVariable Long bookId,
            @RequestBody SetBookTagsRequest request
    ) {
        return tagService.setBookTags(bookId, request);
    }

    /** Tolerante: ignora vacíos y valores no numéricos en vez de tirar 400. */
    private List<Long> parseTagIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String chunk : raw) {
            if (chunk == null || chunk.isBlank()) continue;
            for (String piece : chunk.split(",")) {
                String s = piece.trim();
                if (s.isEmpty()) continue;
                try {
                    ids.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // un id basura no debe romper toda la búsqueda
                }
            }
        }
        return ids;
    }

    @DeleteMapping("/book/{bookId}")
    public ResponseEntity<Void> deleteBookAndReview(@PathVariable Long bookId) {
        reviewService.deleteReviewAndBook(bookId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    // 👉 Crear LIBRO + REVIEW nueva
    @PostMapping
    public ResponseEntity<Review> createReviewForNewBook(
            @RequestBody NewReviewRequest request
    ) {
        Review created = reviewService.createReviewForNewBook(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
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
