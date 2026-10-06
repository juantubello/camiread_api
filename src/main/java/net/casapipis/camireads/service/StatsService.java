package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.repository.StatsRepository;
import net.casapipis.camireads.dto.StatsResponse;
import net.casapipis.camireads.web.projection.AuthorCountView;
import net.casapipis.camireads.web.projection.BestMonthView;
import net.casapipis.camireads.web.projection.MonthCountView;
import net.casapipis.camireads.web.projection.PagesView;
import net.casapipis.camireads.web.projection.RatingBucketView;
import net.casapipis.camireads.web.projection.StatsTotalsView;
import net.casapipis.camireads.web.projection.TopQuotedBookView;
import net.casapipis.camireads.web.projection.YearCountView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * Arma el JSON de GET /stats.
 *
 * REGLA DE RENDIMIENTO (el server de produccion es un i3 de 2 nucleos):
 * este servicio dispara OCHO queries fijas, todas agregaciones de Postgres.
 * No hay ni una query por libro, ni se traen las 1946 resenias a memoria.
 *
 * Lo unico que se calcula en Java son derivados de listas de 6 a 12 elementos
 * que las queries ya trajeron (el mejor anio, el mes mas cargado, el promedio
 * de puntaje como media ponderada del histograma). Eso NO es procesar filas:
 * es aritmetica sobre lo que ya vino agregado, y ahorra queries en vez de
 * gastarlas.
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");

    /** Se devuelve en pages.source para que quede explicito de donde salen. */
    private static final String PAGES_SOURCE = "goodreads_import";

    private final StatsRepository statsRepository;

    @Transactional(readOnly = true)
    public StatsResponse getStats() {

        // "Hoy" se calcula ACA, en horario argentino, y viaja como parametro.
        // El Postgres del server corre en UTC: despues de las 21:00 de
        // Argentina su current_date ya es el dia siguiente, y el corte del
        // "a la misma altura del anio pasado" saldria corrido.
        LocalDate today = LocalDate.now(AR);
        String monthDay = String.format("%02d-%02d", today.getMonthValue(), today.getDayOfMonth());

        StatsTotalsView t = statsRepository.totals(today.getYear(), monthDay);
        List<RatingBucketView> histogram = statsRepository.ratingHistogram();
        List<YearCountView> perYear = statsRepository.booksPerYear();
        List<MonthCountView> perMonth = statsRepository.booksPerMonthOfYear();
        BestMonthView bestMonth = statsRepository.bestMonth();
        AuthorCountView topAuthor = statsRepository.topAuthor();
        TopQuotedBookView topQuoted = statsRepository.topQuotedBook();
        PagesView pages = statsRepository.pages();

        return new StatsResponse(
                new StatsResponse.Totals(
                        t.getTotalBooks(),
                        t.getTotalReviews(),
                        t.getTotalQuotes(),
                        t.getDistinctAuthors()
                ),
                temporalCoverage(t),
                ratings(histogram),
                pages(pages),
                perYear.stream()
                        .map(y -> new StatsResponse.YearCount(y.getYear(), y.getAmount()))
                        .toList(),
                perMonth.stream()
                        .map(m -> new StatsResponse.MonthCount(m.getMonth(), m.getAmount()))
                        .toList(),
                bestYear(perYear),
                busiestMonthOfYear(perMonth),
                bestMonth == null ? null : new StatsResponse.BestMonth(
                        bestMonth.getYear(), bestMonth.getMonth(), bestMonth.getAmount()),
                topAuthor == null ? null : new StatsResponse.TopAuthor(
                        topAuthor.getAuthor(), topAuthor.getAmount()),
                topQuoted == null ? null : new StatsResponse.TopQuotedBook(
                        topQuoted.getBookId(), topQuoted.getTitle(),
                        topQuoted.getAuthor(), topQuoted.getAmount()),
                yearToDate(t, today),
                OffsetDateTime.now(AR)
        );
    }

    // ---------------------------------------------------------------
    // Derivados (aritmetica sobre lo que ya trajeron las queries)
    // ---------------------------------------------------------------

    /**
     * Cuantos libros entran en las metricas temporales y cuantos no.
     *
     * Es el dato que evita que la pantalla mienta: "leiste 1145 libros" seria
     * falso, lo honesto es "de los 1944 libros, 1145 tienen fecha de fin y son
     * los unicos que entran en los graficos por anio y por mes".
     */
    private StatsResponse.TemporalCoverage temporalCoverage(StatsTotalsView t) {
        long with = t.getBooksWithEndDate();
        long without = t.getBooksWithoutEndDate();
        long total = with + without;
        double percent = total == 0 ? 0d : round1(with * 100d / total);
        return new StatsResponse.TemporalCoverage(with, without, percent);
    }

    /**
     * Promedio de puntaje SIN las resenias en 0.
     *
     * rating = 0 es "sin calificar" en esta app, no "malisimo": son ~700 de
     * ~1950. Promediarlas bajaria la nota de 3,77 a 2,41 e inventaria una
     * insatisfaccion que nunca existio. Van contadas aparte, en `unrated`.
     *
     * El promedio sale de las sumas por balde que ya trajimos (6 filas), asi
     * que da identico a un AVG en SQL sin una query extra. Se hace en
     * BigDecimal (los puntajes son cuartos exactos) y recien al final se
     * redondea a 2 decimales: con cuartos de estrella el promedio ya no es
     * "3.8 y algo", y el segundo decimal dice algo.
     */
    private StatsResponse.Ratings ratings(List<RatingBucketView> histogram) {

        long rated = 0;
        long unrated = 0;
        BigDecimal ratedSum = BigDecimal.ZERO;

        for (RatingBucketView bucket : histogram) {
            if (bucket.getRating() == 0) {
                unrated += bucket.getAmount();
            } else {
                rated += bucket.getAmount();
                if (bucket.getRatingSum() != null) {
                    ratedSum = ratedSum.add(bucket.getRatingSum());
                }
            }
        }

        // null y no 0: "todavia no calificó nada" no es lo mismo que
        // "su promedio es cero".
        Double average = rated == 0
                ? null
                : ratedSum.divide(BigDecimal.valueOf(rated), 2, RoundingMode.HALF_UP).doubleValue();

        List<StatsResponse.RatingBucket> distribution = histogram.stream()
                .map(b -> new StatsResponse.RatingBucket(b.getRating(), b.getAmount()))
                .toList();

        return new StatsResponse.Ratings(average, rated, unrated, distribution);
    }

    /** Paginas: totales, sobre cuantos libros, y el promedio por libro. */
    private StatsResponse.Pages pages(PagesView p) {
        Double average = p.getBooksWithPageCount() == 0
                ? null
                : round1((double) p.getTotalPages() / p.getBooksWithPageCount());
        return new StatsResponse.Pages(
                p.getTotalPages(),
                p.getBooksMarkedRead(),
                p.getBooksWithPageCount(),
                average,
                PAGES_SOURCE
        );
    }

    /** El anio con mas libros. Sale de la lista por anio, sin query extra. */
    private StatsResponse.YearCount bestYear(List<YearCountView> perYear) {
        return perYear.stream()
                .max(Comparator.comparingLong(YearCountView::getAmount)
                        .thenComparingInt(YearCountView::getYear))
                .map(y -> new StatsResponse.YearCount(y.getYear(), y.getAmount()))
                .orElse(null);
    }

    /**
     * El mes del anio mas cargado de la estacionalidad (junio y julio empatan
     * en 113 con los datos de hoy; el desempate por numero de mes lo hace
     * determinista y elige junio).
     */
    private StatsResponse.MonthCount busiestMonthOfYear(List<MonthCountView> perMonth) {
        return perMonth.stream()
                .max(Comparator.comparingLong(MonthCountView::getAmount)
                        .thenComparing(Comparator.comparingInt(MonthCountView::getMonth).reversed()))
                .map(m -> new StatsResponse.MonthCount(m.getMonth(), m.getAmount()))
                .orElse(null);
    }

    /** "Vas N libros adelante": este anio contra el pasado a la misma altura. */
    private StatsResponse.YearToDate yearToDate(StatsTotalsView t, LocalDate today) {
        long thisYear = t.getCurrentYearBooks();
        long prevSame = t.getPreviousYearSameDateBooks();
        return new StatsResponse.YearToDate(
                today.getYear(),
                thisYear,
                today.getYear() - 1,
                prevSame,
                t.getPreviousYearTotalBooks(),
                thisYear - prevSame,
                today
        );
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
