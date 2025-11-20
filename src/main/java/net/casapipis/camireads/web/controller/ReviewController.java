package net.casapipis.camireads.web.controller;

import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.service.ReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "*") // para que el front pueda pegarle desde localhost:3000
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    // Spring inyecta ReviewService acá
    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    // GET http://localhost:8080/api/reviews
    @GetMapping
    public List<Review> getAllReviews() {
        return reviewService.getAllReviews();
    }
}
