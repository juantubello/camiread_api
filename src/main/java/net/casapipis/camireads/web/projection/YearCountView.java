package net.casapipis.camireads.web.projection;

/** Libros terminados en un anio calendario (segun books.end_read_date). */
public interface YearCountView {
    int getYear();
    long getAmount();
}
