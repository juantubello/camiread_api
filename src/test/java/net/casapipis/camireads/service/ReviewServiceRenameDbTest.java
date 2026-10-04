package net.casapipis.camireads.service;

import net.casapipis.camireads.domain.model.Book;
import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.domain.repository.BookRepository;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renombrar libros (PUT /reviews/book/{id} con title/author) contra una base
 * REAL: la copia sandbox en 127.0.0.1:5434. NUNCA produccion.
 *
 * Se saltean solos si no hay datasource configurado, asi que el build sigue
 * andando sin base (el Dockerfile compila con -DskipTests igual).
 *
 * Todos los libros que usan los crean ellos mismos con titulos irrepetibles
 * (prefijo "ZZ Test Rename") y los borran en el @AfterEach: los 1944 libros
 * reales de Camila no se tocan ni se cuentan de menos.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class ReviewServiceRenameDbTest {

    private static final String PREFIX = "ZZ Test Rename ";

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdBookIds = new ArrayList<>();

    private long booksBefore;

    @BeforeEach
    void contarLibros() {
        booksBefore = count("books");
        assertTrue(booksBefore > 0, "la base de prueba tiene que tener libros");
    }

    @AfterEach
    void limpiar() {
        for (Long id : createdBookIds) {
            // book_tags, review_quotes y reviews caen por ON DELETE CASCADE,
            // pero los borramos explicito para no depender de eso.
            jdbc.update("DELETE FROM book_tags WHERE book_id = ?", id);
            jdbc.update("DELETE FROM review_quotes WHERE review_id IN "
                    + "(SELECT id FROM reviews WHERE book_id = ?)", id);
            jdbc.update("DELETE FROM reviews WHERE book_id = ?", id);
            jdbc.update("DELETE FROM books WHERE id = ?", id);
        }
        createdBookIds.clear();
        assertEquals(booksBefore, count("books"),
                "el test tiene que dejar la tabla books como la encontro");
    }

    // ─────────────────────────────────────────────────────────────
    // Casos
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Renombrar a un (title, author) que ya existe da 409, no 500, y no pisa nada")
    void renombrarAUnParExistenteDa409() {
        String sufijo = uniqueSuffix();
        String autor = "Autor Test " + sufijo;

        Long ocupadoId = newBookWithReview(PREFIX + "Ocupado " + sufijo, autor);
        Long victimaId = newBookWithReview(PREFIX + "Victima " + sufijo, autor);

        String tituloOriginal = titleOf(victimaId);

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setTitle(titleOf(ocupadoId));   // choca con el otro libro
        req.setAuthor(autor);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateReview(victimaId, req),
                "renombrar a un par ya existente tiene que fallar controlado");

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), "tiene que ser 409");
        assertNotNull(ex.getReason(), "el 409 tiene que traer un mensaje para el front");
        assertTrue(ex.getReason().toLowerCase().contains("ya existe otro libro"),
                "mensaje poco claro: " + ex.getReason());

        // Y la victima quedo intacta.
        assertEquals(tituloOriginal, titleOf(victimaId), "no se tenia que haber renombrado");
        assertEquals(booksBefore + 2, count("books"), "no se tenia que crear ni borrar ningun libro");
    }

    @Test
    @DisplayName("COMPATIBILIDAD: el PUT sin title ni author (front de produccion) no toca el libro")
    void putSinTituloNiAutorNoTocaElLibro() {
        String sufijo = uniqueSuffix();
        String titulo = PREFIX + "Compat " + sufijo;
        String autor = "Autor Test " + sufijo;

        Long bookId = newBookWithReview(titulo, autor);

        // Exactamente lo que manda hoy el front: rating + texto, sin title/author.
        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setRating(4);
        req.setReviewText("resenia editada desde el front viejo");

        Review updated = reviewService.updateReview(bookId, req);

        assertEquals(4, updated.getRating());
        assertEquals(titulo, titleOf(bookId), "el titulo no se tenia que tocar");
        assertEquals(autor, authorOf(bookId), "el autor no se tenia que tocar");
    }

    @Test
    @DisplayName("Renombrar de verdad: el libro queda con el titulo y autor nuevos")
    void renombrarActualizaTituloYAutor() {
        String sufijo = uniqueSuffix();
        Long bookId = newBookWithReview(PREFIX + "Viejo " + sufijo, "Autor Viejo " + sufijo);

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setTitle("  " + PREFIX + "Nuevo " + sufijo + "  ");   // con espacios: se trimea
        req.setAuthor("Autor Nuevo " + sufijo);

        reviewService.updateReview(bookId, req);

        assertEquals(PREFIX + "Nuevo " + sufijo, titleOf(bookId), "tendria que estar trimeado");
        assertEquals("Autor Nuevo " + sufijo, authorOf(bookId));
    }

    @Test
    @DisplayName("Titulo en blanco da 400 (un libro no puede quedarse sin titulo)")
    void tituloEnBlancoDa400() {
        String sufijo = uniqueSuffix();
        String titulo = PREFIX + "Blanco " + sufijo;
        Long bookId = newBookWithReview(titulo, "Autor Test " + sufijo);

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setTitle("   ");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateReview(bookId, req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(titulo, titleOf(bookId), "el titulo no se tenia que tocar");
    }

    @Test
    @DisplayName("Renombrar 'X' a 'X' es un no-op, no un 409 contra si mismo")
    void renombrarAlMismoValorNoEsError() {
        String sufijo = uniqueSuffix();
        String titulo = PREFIX + "Igual " + sufijo;
        String autor = "Autor Test " + sufijo;
        Long bookId = newBookWithReview(titulo, autor);

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setTitle(titulo);
        req.setAuthor(autor);

        reviewService.updateReview(bookId, req);   // no tiene que tirar nada

        assertEquals(titulo, titleOf(bookId));
        assertEquals(autor, authorOf(bookId));
    }

    @Test
    @DisplayName("Renombrar NO toca los tags del libro (book_tags va por book_id)")
    void renombrarConservaLosTags() {
        String sufijo = uniqueSuffix();
        Long bookId = newBookWithReview(PREFIX + "ConTags " + sufijo, "Autor Test " + sufijo);

        List<Long> tagIds = jdbc.queryForList("SELECT id FROM tags ORDER BY id LIMIT 2", Long.class);
        if (tagIds.isEmpty()) {
            return;   // base sin tags sembrados: no hay nada que probar
        }
        for (Long tagId : tagIds) {
            jdbc.update("INSERT INTO book_tags (book_id, tag_id) VALUES (?, ?)", bookId, tagId);
        }

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setTitle(PREFIX + "ConTags Renombrado " + sufijo);

        reviewService.updateReview(bookId, req);

        List<Long> after = jdbc.queryForList(
                "SELECT tag_id FROM book_tags WHERE book_id = ? ORDER BY tag_id", Long.class, bookId);
        assertEquals(tagIds, after, "el renombre no tiene que tocar los tags del libro");
        assertEquals(PREFIX + "ConTags Renombrado " + sufijo, titleOf(bookId));
    }

    @Test
    @DisplayName("Un PUT de solo title NO borra las frases de la resenia")
    void renombrarNoBorraLasQuotes() {
        String sufijo = uniqueSuffix();
        Long bookId = newBookWithReview(PREFIX + "ConFrases " + sufijo, "Autor Test " + sufijo);

        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM reviews WHERE book_id = ?", Long.class, bookId);
        jdbc.update("INSERT INTO review_quotes (review_id, quote_text) VALUES (?, ?)",
                reviewId, "frase subrayada 1");
        jdbc.update("INSERT INTO review_quotes (review_id, quote_text) VALUES (?, ?)",
                reviewId, "frase subrayada 2");

        UpdateReviewRequest soloTitulo = new UpdateReviewRequest();
        soloTitulo.setTitle(PREFIX + "ConFrases Renombrado " + sufijo);

        reviewService.updateReview(bookId, soloTitulo);

        assertEquals(2L, countQuotes(reviewId),
                "renombrar no tiene que borrar los subrayados de Camila");

        // Y mandar quotes: [] SI las borra (comportamiento de siempre del front).
        UpdateReviewRequest vaciarQuotes = new UpdateReviewRequest();
        vaciarQuotes.setQuotes(List.of());
        reviewService.updateReview(bookId, vaciarQuotes);

        assertEquals(0L, countQuotes(reviewId), "quotes: [] tiene que seguir vaciando");
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Crea un libro descartable con su resenia y lo agenda para el cleanup. */
    private Long newBookWithReview(String title, String author) {
        Long bookId = jdbc.queryForObject(
                "INSERT INTO books (title, author) VALUES (?, ?) RETURNING id",
                Long.class, title, author);
        assertNotNull(bookId);
        createdBookIds.add(bookId);

        jdbc.update("INSERT INTO reviews (book_id, rating, review_text) VALUES (?, ?, ?)",
                bookId, 3, "resenia de prueba");
        return bookId;
    }

    private String titleOf(Long bookId) {
        return jdbc.queryForObject("SELECT title FROM books WHERE id = ?", String.class, bookId);
    }

    private String authorOf(Long bookId) {
        return jdbc.queryForObject("SELECT author FROM books WHERE id = ?", String.class, bookId);
    }

    private long countQuotes(Long reviewId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM review_quotes WHERE review_id = ?", Long.class, reviewId);
        return n == null ? -1 : n;
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? -1 : n;
    }

    /** Sanity: el repositorio nuevo no ve al propio libro como colision. */
    @Test
    @DisplayName("findFirstByTitleAndAuthorAndIdNot ignora al propio libro")
    void elChequeoDeColisionIgnoraAlPropioLibro() {
        String sufijo = uniqueSuffix();
        String titulo = PREFIX + "Self " + sufijo;
        String autor = "Autor Test " + sufijo;
        Long bookId = newBookWithReview(titulo, autor);

        assertTrue(bookRepository.findFirstByTitleAndAuthorAndIdNot(titulo, autor, bookId).isEmpty(),
                "no tiene que considerarse colision consigo mismo");

        Book otro = bookRepository.findById(bookId).orElseThrow();
        assertEquals(titulo, otro.getTitle());
    }
}
