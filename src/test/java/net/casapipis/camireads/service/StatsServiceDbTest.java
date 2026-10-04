package net.casapipis.camireads.service;

import net.casapipis.camireads.dto.StatsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /stats contra la base REAL (la copia sandbox en 127.0.0.1:5434).
 * Se saltea solo si no hay datasource, igual que el resto de los *DbTest.
 *
 * CRITERIO: cada metrica se contrasta contra SQL ESCRITO APARTE en el test,
 * no contra un numero pegado a mano. Si fuera un numero fijo, el test se
 * rompe cada vez que Camila carga un libro y termina desactivado. Asi, en
 * cambio, sigue valiendo con cualquier contenido de la base.
 *
 * Es de SOLO LECTURA: no escribe ni una fila.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class StatsServiceDbTest {

    private static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");

    @Autowired
    private StatsService statsService;

    @Autowired
    private JdbcTemplate jdbc;

    private long sql(String query, Object... args) {
        Long n = jdbc.queryForObject(query, Long.class, args);
        return n == null ? -1 : n;
    }

    @Test
    @DisplayName("TOTALES: libros, resenias, frases y autores coinciden con el count() de la base")
    void totalesCoincidenConSql() {
        StatsResponse.Totals t = statsService.getStats().totals();

        assertEquals(sql("SELECT count(*) FROM books"), t.books());
        assertEquals(sql("SELECT count(*) FROM reviews"), t.reviews());
        assertEquals(sql("SELECT count(*) FROM review_quotes"), t.quotes());
        assertEquals(sql("SELECT count(DISTINCT author) FROM books"), t.distinctAuthors());
    }

    @Test
    @DisplayName("COBERTURA TEMPORAL: los libros sin end_read_date se informan, no se esconden")
    void coberturaTemporalSeInforma() {
        StatsResponse stats = statsService.getStats();
        StatsResponse.TemporalCoverage c = stats.temporalCoverage();

        assertEquals(sql("SELECT count(*) FROM books WHERE end_read_date IS NOT NULL"), c.withEndDate());
        assertEquals(sql("SELECT count(*) FROM books WHERE end_read_date IS NULL"), c.withoutEndDate());

        // Lo importante: las dos partes suman el total. Ningun libro se pierde
        // por el camino y la pantalla puede decir sobre cuantos esta hablando.
        assertEquals(stats.totals().books(), c.withEndDate() + c.withoutEndDate());

        // Y hay algo que informar de verdad: hoy son ~799 libros sin fecha.
        assertTrue(c.withoutEndDate() > 0, "la base de prueba tiene libros sin fecha de fin");
    }

    @Test
    @DisplayName("PUNTAJES: el promedio EXCLUYE los rating 0 ('sin calificar') y los cuenta aparte")
    void promedioExcluyeLasSinCalificar() {
        StatsResponse.Ratings r = statsService.getStats().ratings();

        assertEquals(sql("SELECT count(*) FROM reviews WHERE rating = 0"), r.unrated());
        assertEquals(sql("SELECT count(*) FROM reviews WHERE rating > 0"), r.rated());

        // El promedio de la app tiene que ser el AVG de la base SOLO sobre las
        // calificadas.
        Double avgSql = jdbc.queryForObject(
                "SELECT avg(rating) FROM reviews WHERE rating > 0", Double.class);
        assertNotNull(avgSql);
        assertNotNull(r.average());
        assertEquals(Math.round(avgSql * 100d) / 100d, r.average(), 0.0001);

        // Y tiene que ser DISTINTO de meter los ceros adentro: si alguien
        // "simplifica" el calculo a un AVG pelado, este assert lo caza.
        Double avgConCeros = jdbc.queryForObject("SELECT avg(rating) FROM reviews", Double.class);
        assertNotNull(avgConCeros);
        assertTrue(Math.abs(avgConCeros - r.average()) > 0.5,
                "el promedio no puede estar arrastrando las resenias sin calificar");

        // El histograma cubre las 6 notas y suma el total de resenias.
        assertEquals(6, r.distribution().size());
        assertEquals(sql("SELECT count(*) FROM reviews"),
                r.distribution().stream().mapToLong(StatsResponse.RatingBucket::amount).sum());
        for (StatsResponse.RatingBucket b : r.distribution()) {
            assertEquals(sql("SELECT count(*) FROM reviews WHERE rating = ?", b.rating()),
                    b.amount(), "escalon " + b.rating());
        }
    }

    @Test
    @DisplayName("PAGINAS: salen de goodreads_import (estante 'read') e informan sobre cuantos libros")
    void paginasSalenDeGoodreads() {
        StatsResponse.Pages p = statsService.getStats().pages();

        assertEquals("goodreads_import", p.source());
        assertEquals(sql("SELECT count(*) FROM goodreads_import WHERE btrim(exclusive_shelf) = 'read'"),
                p.booksMarkedRead());
        assertEquals(sql("""
                        SELECT count(*) FROM goodreads_import
                         WHERE btrim(exclusive_shelf) = 'read'
                           AND btrim(coalesce(number_of_pages,'')) <> ''"""),
                p.booksWithPageCount());
        assertEquals(sql("""
                        SELECT coalesce(sum(btrim(number_of_pages)::bigint), 0)
                          FROM goodreads_import
                         WHERE btrim(exclusive_shelf) = 'read'
                           AND btrim(coalesce(number_of_pages,'')) <> ''"""),
                p.totalPages());

        // No todos los libros suman paginas: hay libros cargados a mano en
        // CamiReads que no estan en Goodreads. Por eso booksWithPageCount viaja
        // en el JSON.
        assertTrue(p.booksWithPageCount() <= p.booksMarkedRead());
        assertNotNull(p.averagePages());
        assertEquals(Math.round((double) p.totalPages() / p.booksWithPageCount() * 10d) / 10d,
                p.averagePages(), 0.0001);
    }

    @Test
    @DisplayName("POR ANIO: cada anio coincide con la base y el mejor anio es el maximo de la serie")
    void librosPorAnioYMejorAnio() {
        StatsResponse stats = statsService.getStats();

        for (StatsResponse.YearCount y : stats.booksPerYear()) {
            assertEquals(sql("SELECT count(*) FROM books WHERE extract(year FROM end_read_date) = ?",
                            y.year()),
                    y.amount(), "anio " + y.year());
        }

        // La serie solo puede cubrir los libros CON fecha de fin.
        assertEquals(stats.temporalCoverage().withEndDate(),
                stats.booksPerYear().stream().mapToLong(StatsResponse.YearCount::amount).sum());

        Map<String, Object> best = jdbc.queryForMap("""
                SELECT extract(year FROM end_read_date)::int AS y, count(*) AS n
                  FROM books WHERE end_read_date IS NOT NULL
                 GROUP BY 1 ORDER BY n DESC, y DESC LIMIT 1""");
        assertEquals(((Number) best.get("y")).intValue(), stats.bestYear().year());
        assertEquals(((Number) best.get("n")).longValue(), stats.bestYear().amount());
    }

    @Test
    @DisplayName("ESTACIONALIDAD: los 12 meses estan siempre y cada uno coincide con la base")
    void librosPorMesDelAnio() {
        StatsResponse stats = statsService.getStats();
        List<StatsResponse.MonthCount> meses = stats.booksPerMonthOfYear();

        // Los 12, aunque alguno este en cero: el front dibuja el grafico sin
        // tener que rellenar huecos.
        assertEquals(12, meses.size());
        for (int i = 0; i < 12; i++) {
            assertEquals(i + 1, meses.get(i).month(), "los meses vienen ordenados 1..12");
        }

        for (StatsResponse.MonthCount m : meses) {
            assertEquals(sql("SELECT count(*) FROM books WHERE extract(month FROM end_read_date) = ?",
                            m.month()),
                    m.amount(), "mes " + m.month());
        }

        assertEquals(stats.temporalCoverage().withEndDate(),
                meses.stream().mapToLong(StatsResponse.MonthCount::amount).sum());
    }

    @Test
    @DisplayName("MEJOR MES HISTORICO: coincide con el (anio, mes) mas cargado de la base")
    void mejorMesHistorico() {
        StatsResponse.BestMonth b = statsService.getStats().bestMonth();
        assertNotNull(b);

        Map<String, Object> sqlBest = jdbc.queryForMap("""
                SELECT extract(year FROM end_read_date)::int  AS y,
                       extract(month FROM end_read_date)::int AS m,
                       count(*) AS n
                  FROM books WHERE end_read_date IS NOT NULL
                 GROUP BY 1, 2 ORDER BY n DESC, y DESC, m DESC LIMIT 1""");

        assertEquals(((Number) sqlBest.get("y")).intValue(), b.year());
        assertEquals(((Number) sqlBest.get("m")).intValue(), b.month());
        assertEquals(((Number) sqlBest.get("n")).longValue(), b.amount());
    }

    @Test
    @DisplayName("AUTORA MAS LEIDA y LIBRO CON MAS FRASES coinciden con la base")
    void recordsDeAutoraYFrases() {
        StatsResponse stats = statsService.getStats();

        Map<String, Object> autora = jdbc.queryForMap("""
                SELECT author AS a, count(*) AS n FROM books
                 GROUP BY author ORDER BY n DESC, a ASC LIMIT 1""");
        assertEquals(autora.get("a"), stats.topAuthor().author());
        assertEquals(((Number) autora.get("n")).longValue(), stats.topAuthor().amount());

        Map<String, Object> libro = jdbc.queryForMap("""
                SELECT b.id AS id, b.title AS t, count(q.id) AS n
                  FROM books b
                  JOIN reviews r       ON r.book_id = b.id
                  JOIN review_quotes q ON q.review_id = r.id
                 GROUP BY b.id, b.title ORDER BY n DESC, t ASC LIMIT 1""");
        assertEquals(((Number) libro.get("id")).longValue(), stats.topQuotedBook().bookId());
        assertEquals(libro.get("t"), stats.topQuotedBook().title());
        assertEquals(((Number) libro.get("n")).longValue(), stats.topQuotedBook().amount());
    }

    @Test
    @DisplayName("ANIO EN CURSO vs ANIO PASADO: el anterior va RECORTADO a la misma altura del calendario")
    void comparacionContraElAnioPasadoALaMismaAltura() {
        StatsResponse.YearToDate ytd = statsService.getStats().yearToDate();

        // "Hoy" se calcula en horario argentino, no en el UTC del contenedor de
        // Postgres: despues de las 21:00 de Argentina son dias distintos.
        LocalDate hoy = LocalDate.now(AR);
        assertEquals(hoy, ytd.asOf());
        assertEquals(hoy.getYear(), ytd.year());
        assertEquals(hoy.getYear() - 1, ytd.previousYear());

        String monthDay = String.format("%02d-%02d", hoy.getMonthValue(), hoy.getDayOfMonth());

        assertEquals(sql("SELECT count(*) FROM books WHERE extract(year FROM end_read_date) = ?",
                hoy.getYear()), ytd.booksThisYear());

        assertEquals(sql("""
                        SELECT count(*) FROM books
                         WHERE extract(year FROM end_read_date) = ?
                           AND to_char(end_read_date, 'MM-DD') <= ?""",
                hoy.getYear() - 1, monthDay), ytd.previousYearSameDate());

        assertEquals(sql("SELECT count(*) FROM books WHERE extract(year FROM end_read_date) = ?",
                hoy.getYear() - 1), ytd.previousYearTotal());

        assertEquals(ytd.booksThisYear() - ytd.previousYearSameDate(), ytd.difference());

        // El recorte tiene que recortar de verdad: comparar contra el anio
        // pasado COMPLETO seria hacer trampa (en enero darias siempre atras).
        assertTrue(ytd.previousYearSameDate() <= ytd.previousYearTotal());
    }

    @Test
    @DisplayName("/stats NO escribe: books, reviews y review_quotes quedan igual")
    void statsNoEscribeNada() {
        long books = sql("SELECT count(*) FROM books");
        long reviews = sql("SELECT count(*) FROM reviews");
        long quotes = sql("SELECT count(*) FROM review_quotes");

        statsService.getStats();
        statsService.getStats();

        assertEquals(books, sql("SELECT count(*) FROM books"));
        assertEquals(reviews, sql("SELECT count(*) FROM reviews"));
        assertEquals(quotes, sql("SELECT count(*) FROM review_quotes"));
    }
}
