package net.casapipis.camireads.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class NewReviewRequest {

    private String title;
    private String author;
    private String urlCover;

    private OffsetDateTime startReadDate;
    private OffsetDateTime endReadDate;

    /** 0 (sin calificar) o 0.25..5 en pasos de 0.25. Lo valida ReviewService. */
    private BigDecimal rating;
    private String reviewText;

    private List<String> quotes;
}
