package net.casapipis.camireads.service;

import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.domain.repository.ReviewRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;

    // Inyección de dependencias por constructor
    public ReviewService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    public List<Review> getAllReviews() {
        // esto hace SELECT * FROM reviews
        return reviewRepository.findAll();
    }
}
