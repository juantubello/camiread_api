package net.casapipis.camireads.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /stats — la pantalla de perfil de Camila.
 *
 * TRES ADVERTENCIAS DE DATOS QUE ESTE JSON HACE EXPLICITAS en vez de esconder:
 *
 *  1) temporalCoverage — el 41% de los libros no tiene end_read_date. Todo lo
 *     temporal (booksPerYear, booksPerMonthOfYear, bestYear, bestMonth,
 *     yearToDate) cubre solo los libros CON fecha. Cuantos entran y cuantos
 *     quedan afuera viaja en el JSON para que la pantalla pueda decirlo.
 *
 *  2) ratings.unrated — rating 0 significa "sin calificar", no "malisimo".
 *     Esas resenias estan EXCLUIDAS del promedio y contadas aparte.
 *
 *  3) pages.source — las paginas salen de goodreads_import, no de books. Un
 *     libro cargado solo en CamiReads no suma paginas; sobre cuantos libros se
 *     calculo el total tambien viaja aca.
 */
public record StatsResponse(
        Totals totals,
        TemporalCoverage temporalCoverage,
        Ratings ratings,
        Pages pages,
        List<YearCount> booksPerYear,
        List<MonthCount> booksPerMonthOfYear,
        YearCount bestYear,
        MonthCount busiestMonthOfYear,
        BestMonth bestMonth,
        TopAuthor topAuthor,
        TopQuotedBook topQuotedBook,
        YearToDate yearToDate,
        OffsetDateTime generatedAt
) {

    /** Lo que hay cargado, sin recortes: cuenta TODOS los libros y resenias. */
    public record Totals(
            long books,
            long reviews,
            long quotes,
            long distinctAuthors
    ) {}

    /**
     * Cuantos libros entran en las metricas temporales y cuantos quedan afuera.
     *
     * withEndDate + withoutEndDate == totals.books, siempre. coveragePercent es
     * redondeo a un decimal, pensado para un texto tipo "sobre el 58,9% de tus
     * libros".
     */
    public record TemporalCoverage(
            long withEndDate,
            long withoutEndDate,
            double coveragePercent
    ) {}

    /**
     * average NO incluye las resenias con rating 0 ("sin calificar"): se
     * calcula solo sobre las `rated`. Si no hubiera ninguna calificada,
     * average viene null (no 0, que se leeria como "le pusieron cero").
     *
     * distribution trae los 6 escalones, el 0 incluido, para poder dibujar el
     * histograma completo.
     */
    public record Ratings(
            Double average,
            long rated,
            long unrated,
            List<RatingBucket> distribution
    ) {}

    public record RatingBucket(int rating, long amount) {}

    /**
     * source dice de donde salen las paginas ("goodreads_import") y
     * booksWithPageCount sobre cuantos libros se sumaron, porque NO son todos:
     * un libro cargado a mano en CamiReads no tiene paginas en ningun lado.
     * average puede ser null si no hay ni un libro con paginas.
     */
    public record Pages(
            long totalPages,
            long booksMarkedRead,
            long booksWithPageCount,
            Double averagePages,
            String source
    ) {}

    public record YearCount(int year, long amount) {}

    public record MonthCount(int month, long amount) {}

    public record BestMonth(int year, int month, long amount) {}

    public record TopAuthor(String author, long amount) {}

    public record TopQuotedBook(Long bookId, String title, String author, long amount) {}

    /**
     * El "vas N libros adelante".
     *
     * previousYearSameDate es el anio pasado RECORTADO al mismo mes y dia que
     * hoy — comparar contra el anio pasado completo seria trampa en enero.
     * previousYearTotal viene igual, de yapa, para poder mostrar "de X que
     * leiste en todo el anio".
     *
     * difference = booksThisYear - previousYearSameDate (negativo si va atras).
     * asOf es la fecha de corte, en horario argentino.
     */
    public record YearToDate(
            int year,
            long booksThisYear,
            int previousYear,
            long previousYearSameDate,
            long previousYearTotal,
            long difference,
            LocalDate asOf
    ) {}
}
