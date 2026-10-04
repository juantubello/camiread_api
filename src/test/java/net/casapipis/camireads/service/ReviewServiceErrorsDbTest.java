package net.casapipis.camireads.service;

import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.dto.NewReviewRequest;
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
 * Los dos 500 que se arreglaron (2026-09), contra la base REAL de sandbox
 * (127.0.0.1:5434). NUNCA produccion.
 *
 *  1. POST /reviews con reviewText null reventaba la constraint NOT NULL de
 *     reviews.review_text y le mostraba el SQL crudo a la usuaria.
 *  2. PUT /reviews/book/{id} sobre un id inexistente tiraba
 *     EntityNotFoundException, que Spring no mapea => 500 en vez de 404.
 *
 * Se saltean solos si no hay datasource configurado (igual que los otros
 * tests de base), asi que el build del Dockerfile sigue andando sin base.
 *
 * Todo lo que crean lo borran en el @AfterEach y ademas verifican que la
 * tabla books quede con el mismo conteo con el que arrancaron: los libros de
 * Camila no se tocan.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class ReviewServiceErrorsDbTest {

    private static final String PREFIX = "ZZ Test Errores ";

    /** Id que no existe ni va a existir (la secuencia de books esta lejisimos). */
    private static final long ID_INEXISTENTE = 999999L;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdBookIds = new ArrayList<>();

    private long booksBefore;
    private long quotesBefore;

    @BeforeEach
    void contar() {
        booksBefore = count("books");
        quotesBefore = count("review_quotes");
        assertTrue(booksBefore > 0, "la base de prueba tiene que tener libros");

        // Sanity: el id que usamos para los 404 no puede existir de verdad.
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM books WHERE id = ?", Long.class, ID_INEXISTENTE));
    }

    @AfterEach
    void limpiar() {
        for (Long id : createdBookIds) {
            jdbc.update("DELETE FROM book_tags WHERE book_id = ?", id);
            jdbc.update("DELETE FROM review_quotes WHERE review_id IN "
                    + "(SELECT id FROM reviews WHERE book_id = ?)", id);
            jdbc.update("DELETE FROM reviews WHERE book_id = ?", id);
            jdbc.update("DELETE FROM books WHERE id = ?", id);
        }
        createdBookIds.clear();
        assertEquals(booksBefore, count("books"),
                "el test tiene que dejar la tabla books como la encontro");
        assertEquals(quotesBefore, count("review_quotes"),
                "el test no tiene que tocar las frases de Camila");
    }

    // ─────────────────────────────────────────────────────────────
    // BUG 1 — alta sin texto de resenia
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUG 1: POST con reviewText null guarda cadena vacia, no revienta la NOT NULL")
    void altaConTextoNullGuardaCadenaVacia() {
        String sufijo = uniqueSuffix();

        NewReviewRequest req = base(PREFIX + "SinTexto " + sufijo, sufijo);
        req.setReviewText(null);

        Review created = track(reviewService.createReviewForNewBook(req));

        assertNotNull(created.getId());
        assertEquals("", created.getReviewText(), "sin texto se guarda como cadena vacia");
        assertEquals("", reviewTextOf(created.getId()), "y asi queda en la base, no null");
        assertEquals(4, created.getRating(), "el puntaje si se guarda: el libro queda puntuado");
    }

    @Test
    @DisplayName("BUG 1: POST con reviewText \"\" se comporta igual que con null")
    void altaConTextoVacioGuardaCadenaVacia() {
        String sufijo = uniqueSuffix();

        NewReviewRequest req = base(PREFIX + "TextoVacio " + sufijo, sufijo);
        req.setReviewText("");

        Review created = track(reviewService.createReviewForNewBook(req));

        assertEquals("", reviewTextOf(created.getId()));
    }

    @Test
    @DisplayName("El alta normal (con texto) sigue funcionando igual")
    void altaNormalSigueAndando() {
        String sufijo = uniqueSuffix();

        NewReviewRequest req = base(PREFIX + "Normal " + sufijo, sufijo);
        req.setReviewText("me encantó, lo leí de una sentada");
        req.setQuotes(List.of("una frase subrayada", "  ", "otra frase"));

        Review created = track(reviewService.createReviewForNewBook(req));

        assertEquals("me encantó, lo leí de una sentada", reviewTextOf(created.getId()));
        assertEquals(2L, countQuotes(created.getId()), "las frases en blanco se descartan");
    }

    @Test
    @DisplayName("COHERENCIA POST/PUT: el PUT con reviewText null NO pisa el texto que ya estaba")
    void putConTextoNullNoPisaElTexto() {
        String sufijo = uniqueSuffix();

        NewReviewRequest alta = base(PREFIX + "Coherencia " + sufijo, sufijo);
        alta.setReviewText("texto original");
        Review created = track(reviewService.createReviewForNewBook(alta));

        UpdateReviewRequest soloPuntaje = new UpdateReviewRequest();
        soloPuntaje.setRating(5);
        reviewService.updateReview(created.getBook().getId(), soloPuntaje);

        assertEquals("texto original", reviewTextOf(created.getId()),
                "en el PUT, null significa 'no tocar' (igual que quotes)");

        // Y mandar "" SI lo vacia, quedando en cadena vacia (nunca null).
        UpdateReviewRequest vaciar = new UpdateReviewRequest();
        vaciar.setReviewText("");
        reviewService.updateReview(created.getBook().getId(), vaciar);

        assertEquals("", reviewTextOf(created.getId()));
    }

    @Test
    @DisplayName("POST sin titulo da 400 legible (no un 500 con la NOT NULL de books.title)")
    void altaSinTituloDa400() {
        NewReviewRequest req = base(null, uniqueSuffix());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> track(reviewService.createReviewForNewBook(req)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().toLowerCase().contains("título"), ex.getReason());
    }

    @Test
    @DisplayName("POST sin rating da 400 legible (antes era NullPointerException => 500)")
    void altaSinRatingDa400() {
        String sufijo = uniqueSuffix();
        NewReviewRequest req = base(PREFIX + "SinRating " + sufijo, sufijo);
        req.setRating(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> track(reviewService.createReviewForNewBook(req)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().toLowerCase().contains("puntaje"), ex.getReason());
    }

    @Test
    @DisplayName("POST de un (titulo, autor) que ya existe da 409, no 500 del indice unico")
    void altaDuplicadaDa409() {
        String sufijo = uniqueSuffix();
        String titulo = PREFIX + "Duplicado " + sufijo;

        track(reviewService.createReviewForNewBook(base(titulo, sufijo)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> track(reviewService.createReviewForNewBook(base(titulo, sufijo))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().toLowerCase().contains("ya existe otro libro"), ex.getReason());
        assertEquals(booksBefore + 1, count("books"), "el duplicado no se tiene que haber creado");
    }

    // ─────────────────────────────────────────────────────────────
    // BUG 2 — 404 en vez de 500 sobre un libro inexistente
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUG 2: PUT a un libro inexistente da 404 con mensaje, no 500")
    void putALibroInexistenteDa404() {
        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setRating(5);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> reviewService.updateReview(ID_INEXISTENTE, req));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertNotNull(ex.getReason(), "el 404 tiene que traer un mensaje para el front");
        assertTrue(ex.getReason().contains(String.valueOf(ID_INEXISTENTE)), ex.getReason());
    }

    @Test
    @DisplayName("BUG 2: GET a un libro inexistente da el MISMO 404 que el PUT")
    void getALibroInexistenteDa404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> reviewService.getReviewByBookIdOrFail(ID_INEXISTENTE));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("No existe ningún libro con id " + ID_INEXISTENTE, ex.getReason());
    }

    @Test
    @DisplayName("BUG 2: DELETE a un libro inexistente da 404, no 500")
    void deleteALibroInexistenteDa404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> reviewService.deleteReviewAndBook(ID_INEXISTENTE));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("No existe ningún libro con id " + ID_INEXISTENTE, ex.getReason());
    }

    @Test
    @DisplayName("Un libro que existe pero no tiene resenia tambien da 404 (mensaje distinto)")
    void libroSinReseniaDa404() {
        String sufijo = uniqueSuffix();
        Long bookId = jdbc.queryForObject(
                "INSERT INTO books (title, author) VALUES (?, ?) RETURNING id",
                Long.class, PREFIX + "SinResenia " + sufijo, "Autor Test " + sufijo);
        assertNotNull(bookId);
        createdBookIds.add(bookId);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> reviewService.getReviewByBookIdOrFail(bookId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getReason().contains("no tiene ninguna reseña"), ex.getReason());
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Alta valida "de base", a la que cada test le cambia lo que quiere probar. */
    private NewReviewRequest base(String titulo, String sufijo) {
        NewReviewRequest req = new NewReviewRequest();
        req.setTitle(titulo);
        req.setAuthor("Autor Test " + sufijo);
        req.setRating(4);
        req.setReviewText("resenia de prueba");
        return req;
    }

    /** Agenda el libro creado para el cleanup del @AfterEach. */
    private Review track(Review review) {
        if (review != null && review.getBook() != null && review.getBook().getId() != null) {
            createdBookIds.add(review.getBook().getId());
        }
        return review;
    }

    private String reviewTextOf(Long reviewId) {
        return jdbc.queryForObject("SELECT review_text FROM reviews WHERE id = ?",
                String.class, reviewId);
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
}
