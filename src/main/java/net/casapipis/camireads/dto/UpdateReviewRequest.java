package net.casapipis.camireads.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class UpdateReviewRequest {

    private OffsetDateTime startReadDate;
    private OffsetDateTime endReadDate;

    private Integer rating;
    private String reviewText;

    private List<String> quotes;

    private String urlCover;

    public OffsetDateTime getStartReadDate() {
        return startReadDate;
    }

    public void setStartReadDate(OffsetDateTime startReadDate) {
        this.startReadDate = startReadDate;
    }

    public OffsetDateTime getEndReadDate() {
        return endReadDate;
    }

    public void setEndReadDate(OffsetDateTime endReadDate) {
        this.endReadDate = endReadDate;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getReviewText() {
        return reviewText;
    }

    public void setReviewText(String reviewText) {
        this.reviewText = reviewText;
    }

    public List<String> getQuotes() {
        return quotes;
    }

    public void setQuotes(List<String> quotes) {
        this.quotes = quotes;
    }

    public String getUrlCover() {
        return urlCover;
    }

    public void setUrlCover(String urlCover) {
        this.urlCover = urlCover;
    }

}
