package net.casapipis.camireads.dto.saga;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un libro dentro de una saga.
 *
 * urlCover: books.url_cover si tiene; si no, la b64 como data URL; si no, null.
 * rating: el de la resenia, o 0 si el libro no tiene (Fase 8: libros sin leer).
 * endReadDate: books.end_read_date es DATE, sale como "YYYY-MM-DD".
 */
public record SagaBook(
        int position,
        Long bookId,
        String title,
        String author,
        String urlCover,
        BigDecimal rating,
        LocalDate endReadDate
) {
}
