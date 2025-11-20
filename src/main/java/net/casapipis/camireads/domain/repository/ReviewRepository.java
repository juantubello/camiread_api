package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    // findAll() ya viene de JpaRepository
}
