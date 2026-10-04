package net.casapipis.camireads.web.projection;

/**
 * Los contadores de una sola fila de GET /stats, todos en UNA query
 * (ver StatsRepository.totals). Incluye la comparacion contra el anio
 * pasado a la misma altura del calendario.
 */
public interface StatsTotalsView {
    long getTotalBooks();
    long getTotalReviews();
    long getTotalQuotes();
    long getDistinctAuthors();
    long getBooksWithEndDate();
    long getBooksWithoutEndDate();
    long getCurrentYearBooks();
    long getPreviousYearSameDateBooks();
    long getPreviousYearTotalBooks();
}
