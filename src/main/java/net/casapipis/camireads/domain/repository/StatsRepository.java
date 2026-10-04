package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Book;
import net.casapipis.camireads.web.projection.AuthorCountView;
import net.casapipis.camireads.web.projection.BestMonthView;
import net.casapipis.camireads.web.projection.MonthCountView;
import net.casapipis.camireads.web.projection.PagesView;
import net.casapipis.camireads.web.projection.RatingBucketView;
import net.casapipis.camireads.web.projection.StatsTotalsView;
import net.casapipis.camireads.web.projection.TopQuotedBookView;
import net.casapipis.camireads.web.projection.YearCountView;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Las metricas de GET /stats. TODO se resuelve con agregaciones en Postgres:
 * ocho queries fijas, ninguna por libro, y en ningun caso se traen las 1946
 * resenias a memoria para contarlas en Java.
 *
 * Por que importa: el server de produccion es un i3 de 2 nucleos. Una query por
 * libro serian ~1950 round-trips; asi son 8, sobre tablas de menos de 2000
 * filas, y el trabajo lo hace la base que es para lo que esta.
 *
 * Extiende Repository (no JpaRepository) a proposito: esto es SOLO LECTURA.
 * No hay save(), ni delete(), ni deleteAll() heredados que alguien pueda
 * llamar por accidente sobre books.
 *
 * Las queries son nativas y los alias van ENTRECOMILLADOS ("totalBooks") porque
 * Postgres baja a minusculas todo alias sin comillas y entonces la proyeccion
 * por interfaz no encontraria el getter.
 */
public interface StatsRepository extends Repository<Book, Long> {

    /**
     * Query 1/8 — todos los contadores de una fila, de un saque.
     *
     * Incluye la comparacion "vas N libros adelante": el anio en curso contra
     * el anterior RECORTADO a la misma altura del calendario (mismo mes y dia).
     *
     * El recorte se hace con to_char(end_read_date, 'MM-DD') <= :monthDay en vez
     * de construir una fecha: comparar 'MM-DD' con ceros a la izquierda es
     * cronologico, y ademas no explota un 29 de febrero (make_date del anio
     * anterior tiraria error ese dia).
     *
     * El anio y la fecha de corte llegan por parametro, calculados en horario
     * argentino: el contenedor de Postgres corre en UTC y despues de las 21:00
     * de Argentina ya esta en el dia siguiente.
     */
    @Query(value = """
            SELECT (SELECT count(*) FROM books)                 AS "totalBooks",
                   (SELECT count(*) FROM reviews)               AS "totalReviews",
                   (SELECT count(*) FROM review_quotes)         AS "totalQuotes",
                   (SELECT count(DISTINCT author) FROM books)   AS "distinctAuthors",
                   (SELECT count(*) FROM books
                     WHERE end_read_date IS NOT NULL)           AS "booksWithEndDate",
                   (SELECT count(*) FROM books
                     WHERE end_read_date IS NULL)               AS "booksWithoutEndDate",
                   (SELECT count(*) FROM books
                     WHERE end_read_date IS NOT NULL
                       AND EXTRACT(YEAR FROM end_read_date) = :year) AS "currentYearBooks",
                   (SELECT count(*) FROM books
                     WHERE end_read_date IS NOT NULL
                       AND EXTRACT(YEAR FROM end_read_date) = :year - 1
                       AND to_char(end_read_date, 'MM-DD') <= :monthDay)
                                                                AS "previousYearSameDateBooks",
                   (SELECT count(*) FROM books
                     WHERE end_read_date IS NOT NULL
                       AND EXTRACT(YEAR FROM end_read_date) = :year - 1)
                                                                AS "previousYearTotalBooks"
            """, nativeQuery = true)
    StatsTotalsView totals(@Param("year") int year, @Param("monthDay") String monthDay);

    /**
     * Query 2/8 — histograma de puntajes, 6 filas (0 a 5).
     *
     * De aca salen TRES numeros sin queries extra: el total de resenias, las
     * "sin calificar" (rating 0) y el promedio real. El promedio se calcula en
     * Java como media ponderada de 6 baldes, que da exactamente lo mismo que
     * un AVG en SQL pero sin una segunda pasada por la tabla.
     *
     * ⚠️ rating = 0 significa "SIN CALIFICAR" en esta app, no "malisimo": son
     * 702 resenias. Meterlas en el promedio lo hundiria de 3,77 a 2,41 y seria
     * una mentira. Se cuentan aparte, nunca se promedian.
     */
    @Query(value = """
            SELECT rating AS "rating", count(*) AS "amount"
            FROM reviews
            GROUP BY rating
            ORDER BY rating
            """, nativeQuery = true)
    List<RatingBucketView> ratingHistogram();

    /**
     * Query 3/8 — libros terminados por anio calendario.
     *
     * De aca sale tambien el "mejor anio" (el maximo de esta lista), sin query
     * aparte.
     *
     * ⚠️ Cubre SOLO los libros con end_read_date. Los 799 sin fecha quedan
     * afuera de esta y de toda metrica temporal; cuantos son viaja en
     * temporalCoverage para que la pantalla lo pueda decir.
     */
    @Query(value = """
            SELECT EXTRACT(YEAR FROM end_read_date)::int AS "year",
                   count(*)                              AS "amount"
            FROM books
            WHERE end_read_date IS NOT NULL
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<YearCountView> booksPerYear();

    /**
     * Query 4/8 — estacionalidad: libros por mes DEL ANIO, sumando todos los anios.
     *
     * El generate_series(1,12) esta para que los 12 meses aparezcan siempre,
     * incluso uno en cero: si no, el front tendria que rellenar huecos para
     * dibujar el grafico.
     */
    @Query(value = """
            SELECT m.month                AS "month",
                   count(b.id)            AS "amount"
            FROM generate_series(1, 12) AS m(month)
            LEFT JOIN books b
                   ON b.end_read_date IS NOT NULL
                  AND EXTRACT(MONTH FROM b.end_read_date) = m.month
            GROUP BY m.month
            ORDER BY m.month
            """, nativeQuery = true)
    List<MonthCountView> booksPerMonthOfYear();

    /**
     * Query 5/8 — el mes concreto (anio + mes) mas leido de la historia.
     *
     * ORDER BY ... LIMIT 1 en vez de traer los ~60 meses y buscar el maximo en
     * Java. Los desempates por anio y mes descendente son para que el resultado
     * sea determinista si dos meses empatan.
     */
    @Query(value = """
            SELECT EXTRACT(YEAR FROM end_read_date)::int  AS "year",
                   EXTRACT(MONTH FROM end_read_date)::int AS "month",
                   count(*)                               AS "amount"
            FROM books
            WHERE end_read_date IS NOT NULL
            GROUP BY 1, 2
            ORDER BY count(*) DESC, 1 DESC, 2 DESC
            LIMIT 1
            """, nativeQuery = true)
    BestMonthView bestMonth();

    /**
     * Query 6/8 — la autora mas leida.
     *
     * Cuenta LIBROS CARGADOS, no libros terminados: no filtra por end_read_date.
     * Es a proposito — "la autora de la que mas tengo" no deberia depender de
     * si Camila se acordo de anotar la fecha de fin.
     *
     * Agrupa por books.author tal cual esta guardado (igual que el indice
     * idx_books_author). No normaliza mayusculas ni espacios: si el dia de
     * maniana aparecen "Rina Kent" y "rina kent" como autoras distintas, eso es
     * un problema de datos a arreglar en books, no a tapar aca.
     */
    @Query(value = """
            SELECT author   AS "author",
                   count(*) AS "amount"
            FROM books
            GROUP BY author
            ORDER BY count(*) DESC, author ASC
            LIMIT 1
            """, nativeQuery = true)
    AuthorCountView topAuthor();

    /** Query 7/8 — el libro con mas frases subrayadas. */
    @Query(value = """
            SELECT b.id       AS "bookId",
                   b.title    AS "title",
                   b.author   AS "author",
                   count(q.id) AS "amount"
            FROM books b
            JOIN reviews r       ON r.book_id = b.id
            JOIN review_quotes q ON q.review_id = r.id
            GROUP BY b.id, b.title, b.author
            ORDER BY count(q.id) DESC, b.title ASC
            LIMIT 1
            """, nativeQuery = true)
    TopQuotedBookView topQuotedBook();

    /**
     * Query 8/8 — paginas leidas.
     *
     * ⚠️ SALEN DE goodreads_import, NO DE books: books no tiene columna de
     * paginas. Un libro cargado a mano en CamiReads y que no vino de Goodreads
     * NO suma paginas, por eso el endpoint devuelve tambien sobre cuantos
     * libros se calculo el numero.
     *
     * Se filtra por exclusive_shelf = 'read' (los OTROS estantes son
     * 'to-read', 'currently-reading' y 'sagas-por-terminar': libros que no
     * termino de leer y cuyas paginas no corresponde sumar).
     *
     * number_of_pages es TEXT: el nullif() saca los vacios y el ::bigint no
     * falla porque los 1011 valores no vacios son numericos puros (verificado
     * con ~ '^[0-9]+$').
     */
    @Query(value = """
            SELECT count(*)                                             AS "booksMarkedRead",
                   count(nullif(btrim(number_of_pages), ''))            AS "booksWithPageCount",
                   coalesce(sum(nullif(btrim(number_of_pages), '')::bigint), 0) AS "totalPages"
            FROM goodreads_import
            WHERE btrim(exclusive_shelf) = 'read'
            """, nativeQuery = true)
    PagesView pages();
}
