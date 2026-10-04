package net.casapipis.camireads.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class UpdateReviewRequest {

    /**
     * Titulo nuevo del LIBRO. OPCIONAL y ADITIVO.
     *
     * Si llega null o directamente ausente en el JSON, el titulo no se toca:
     * el front que corre hoy en produccion manda este PUT sin title ni author
     * y tiene que seguir funcionando igual.
     *
     * Si llega presente pero vacio o en blanco -> 400 (un libro no puede
     * quedarse sin titulo).
     */
    private String title;

    /**
     * Autor nuevo del LIBRO. OPCIONAL y ADITIVO. Mismas reglas que title.
     */
    private String author;

    private OffsetDateTime startReadDate;
    private OffsetDateTime endReadDate;

    private Integer rating;
    private String reviewText;

    private List<String> quotes;

    private String urlCover;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

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
