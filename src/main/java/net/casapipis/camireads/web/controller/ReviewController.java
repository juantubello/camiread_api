package net.casapipis.camireads.web.controller;

import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;


@CrossOrigin(origins = "http://localhost:3000") // 👈 o "*" mientras desarrollás
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
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime reviewFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime reviewTo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime readFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime readTo
    ) {
        return reviewService.searchReviews(
                author,
                bookTitle,
                rating,
                reviewFrom,
                reviewTo,
                readFrom,
                readTo
        );
    }


    @GetMapping("/latest")
    public Page<Review> getLatestReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        // aseguramos orden DESC por fecha, por las dudas
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return reviewService.getLatestReviews(pageable);
    }

}
