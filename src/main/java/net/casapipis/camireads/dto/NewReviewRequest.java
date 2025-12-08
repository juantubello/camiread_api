package net.casapipis.camireads.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class NewReviewRequest {

    private String title;
    private String author;
    private String urlCover;

    private OffsetDateTime startReadDate;
    private OffsetDateTime endReadDate;

    private Integer rating;
    private String reviewText;

    private List<String> quotes;
}
