package net.casapipis.camireads.web.projection;

/** El libro con mas frases subrayadas. */
public interface TopQuotedBookView {
    Long getBookId();
    String getTitle();
    String getAuthor();
    long getAmount();
}
