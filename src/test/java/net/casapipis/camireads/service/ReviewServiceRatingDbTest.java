package net.casapipis.camireads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase 9 — calificacion en cuartos de estrella, contra la base REAL de sandbox
 * (127.0.0.1:5434, ya migrada a numeric(3,2)). NUNCA produccion.
 *
 * Cubre las tres puntas del cambio:
 *  1. Alta y edicion: 3.25 se guarda exacto, un PUT parcial no pisa un 3.75 y
 *     lo que no es cuarto (3.3, 5.25, -0.25...) da 400 legible, no el 500 del
 *     CHECK de Postgres.
 *  2. Busqueda ?rating=N: es un rango [N, N+1) en TODOS los caminos (solo
 *     rating, autor, titulo, autor + titulo), con 0 y 5 exactos y los 0.25..0.75
 *     en 1★ (no en "sin calificar").
 *  3. JSON: el rating sale como numero, nunca como string ni como 4E+0.
 *
 * Igual que ReviewServiceErrorsDbTest: todo lo que crea lo borra en el
 * @AfterEach y verifica que books, reviews y review_quotes queden con el mismo
 * conteo con el que arrancaron.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class ReviewServiceRatingDbTest {

    private static final String PREFIX = "ZZ Test Rating ";

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> createdBookIds = new ArrayList<>();

    private long booksBefore;
    private long reviewsBefore;
    private long quotesBefore;

    @BeforeEach
    void contar() {
        booksBefore = count("books");
        reviewsBefore = count("reviews");
        quotesBefore = count("review_quotes");
        assertTrue(booksBefore > 0, "la base de prueba tiene que tener libros");
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
        assertEquals(booksBefore, count("books"), "el test tiene que dejar books como la encontro");
        assertEquals(reviewsBefore, count("reviews"), "el test tiene que dejar reviews como la encontro");
        assertEquals(quotesBefore, count("review_quotes"), "el test no tiene que tocar las frases de Camila");
    }

    // ─────────────────────────────────────────────────────────────
    // Alta / edicion
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST con 3.25 guarda 3.25 exacto (no redondea a 3)")
    void altaConCuartoSeGuardaExacto() {
        String sufijo = uniqueSuffix();
        Review created = create("Cuarto " + sufijo, sufijo, "3.25");

        assertEquals(new BigDecimal("3.25"), created.getRating());
        assertEquals(new BigDecimal("3.25"), ratingOf(created.getId()));
    }

    @Test
    @DisplayName("POST normaliza la escala: 3.5 y 3.500 se guardan igual, como 3.50")
    void altaNormalizaLaEscala() {
        String sufijo = uniqueSuffix();
        Review a = create("Escala A " + sufijo, sufijo, "3.5");
        Review b = create("Escala B " + sufijo, sufijo, "3.500");

        assertEquals(new BigDecimal("3.50"), a.getRating());
        assertEquals(new BigDecimal("3.50"), b.getRating());
        assertEquals(new BigDecimal("3.50"), ratingOf(b.getId()));
    }

    @Test
    @DisplayName("Los extremos validos pasan: 0 (sin calificar), 0.25 y 5")
    void extremosValidos() {
        String sufijo = uniqueSuffix();
        assertEquals(new BigDecimal("0.00"), create("Cero " + sufijo, sufijo, "0").getRating());
        assertEquals(new BigDecimal("0.25"), create("Minimo " + sufijo, sufijo, "0.25").getRating());
        assertEquals(new BigDecimal("5.00"), create("Maximo " + sufijo, sufijo, "5").getRating());
    }

    @Test
    @DisplayName("PUT parcial (solo title) NO toca un 3.75")
    void putParcialNoPisaElCuarto() {
        String sufijo = uniqueSuffix();
        Review created = create("Parcial " + sufijo, sufijo, "3.75");

        UpdateReviewRequest soloTitulo = new UpdateReviewRequest();
        soloTitulo.setTitle(PREFIX + "Parcial renombrado " + sufijo);
        reviewService.updateReview(created.getBook().getId(), soloTitulo);

        assertEquals(new BigDecimal("3.75"), ratingOf(created.getId()),
                "null = no tocar: el PUT parcial no puede redondear ni pisar el puntaje");
    }

    @Test
    @DisplayName("PUT con un cuarto valido lo guarda (3.75 -> 4.25)")
    void putConCuartoValido() {
        String sufijo = uniqueSuffix();
        Review created = create("PutValido " + sufijo, sufijo, "3.75");

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setRating(new BigDecimal("4.25"));
        reviewService.updateReview(created.getBook().getId(), req);

        assertEquals(new BigDecimal("4.25"), ratingOf(created.getId()));
    }

    @Test
    @DisplayName("PUT con 3.3 / 5.25 / -0.25 / 3.333 da 400 legible y no toca el puntaje")
    void putConPuntajeInvalidoDa400() {
        String sufijo = uniqueSuffix();
        Review created = create("PutInvalido " + sufijo, sufijo, "3.75");
        Long bookId = created.getBook().getId();

        for (String invalido : List.of("3.3", "5.25", "-0.25", "3.333")) {
            UpdateReviewRequest req = new UpdateReviewRequest();
            req.setRating(new BigDecimal(invalido));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> reviewService.updateReview(bookId, req), invalido);

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), invalido);
            assertNotNull(ex.getReason());
            assertTrue(ex.getReason().contains("0,25"), ex.getReason());
            assertTrue(ex.getReason().contains(invalido), "el mensaje dice que llego: " + ex.getReason());
        }

        assertEquals(new BigDecimal("3.75"), ratingOf(created.getId()),
                "un PUT rechazado no deja nada a medio guardar");
    }

    @Test
    @DisplayName("POST con 3.3 da 400 y no crea el libro")
    void altaConPuntajeInvalidoDa400() {
        String sufijo = uniqueSuffix();
        NewReviewRequest req = base(PREFIX + "AltaInvalida " + sufijo, sufijo, "3.3");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> track(reviewService.createReviewForNewBook(req)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(booksBefore, count("books"), "el alta rechazada no deja el libro creado");
    }

    // ─────────────────────────────────────────────────────────────
    // Busqueda ?rating=N
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUSQUEDA: rating=3 incluye un 3.75 y NO un 4.00, en todos los caminos")
    void busquedaPorRangoEnTodosLosCaminos() {
        String sufijo = uniqueSuffix();
        String autor = autorDe(sufijo);
        Review tresTresCuartos = create("Rango 375 " + sufijo, sufijo, "3.75");
        Review cuatro = create("Rango 400 " + sufijo, sufijo, "4");
        String titulo = PREFIX + "Rango";

        // autor + rating
        Set<Long> porAutor = ids(reviewService.searchReviews(autor, null, 3, null, null, null, null));
        assertTrue(porAutor.contains(tresTresCuartos.getId()), "3★ incluye el 3.75");
        assertFalse(porAutor.contains(cuatro.getId()), "3★ NO incluye el 4.00");

        // titulo + rating
        Set<Long> porTitulo = ids(reviewService.searchReviews(null, titulo + " 375 " + sufijo, 3,
                null, null, null, null));
        assertEquals(Set.of(tresTresCuartos.getId()), porTitulo);

        // autor + titulo + rating (la query nativa)
        Set<Long> porAmbos = ids(reviewService.searchReviews(autor, titulo, 3, null, null, null, null));
        assertEquals(Set.of(tresTresCuartos.getId()), porAmbos);

        // solo rating
        Set<Long> soloRating = ids(reviewService.searchReviews(null, null, 3, null, null, null, null));
        assertTrue(soloRating.contains(tresTresCuartos.getId()));
        assertFalse(soloRating.contains(cuatro.getId()));

        // Y el 4.00 si esta en 4★, por los mismos caminos.
        assertEquals(Set.of(cuatro.getId()),
                ids(reviewService.searchReviews(autor, null, 4, null, null, null, null)));
        assertEquals(Set.of(cuatro.getId()),
                ids(reviewService.searchReviews(autor, titulo, 4, null, null, null, null)));
    }

    @Test
    @DisplayName("BUSQUEDA: rating=1 incluye un 0.5; rating=0 NO lo incluye (solo el 0 exacto)")
    void medioPuntoCaeEnUnaEstrellaNoEnSinCalificar() {
        String sufijo = uniqueSuffix();
        String autor = autorDe(sufijo);
        Review medio = create("Medio " + sufijo, sufijo, "0.5");
        Review sinCalificar = create("SinCalificar " + sufijo, sufijo, "0");

        assertEquals(Set.of(medio.getId()),
                ids(reviewService.searchReviews(autor, null, 1, null, null, null, null)));
        assertEquals(Set.of(sinCalificar.getId()),
                ids(reviewService.searchReviews(autor, null, 0, null, null, null, null)));

        // Mismo resultado sin autor (camino "solo rating", sobre toda la base).
        Set<Long> unaEstrella = ids(reviewService.searchReviews(null, null, 1, null, null, null, null));
        Set<Long> cero = ids(reviewService.searchReviews(null, null, 0, null, null, null, null));
        assertTrue(unaEstrella.contains(medio.getId()));
        assertFalse(cero.contains(medio.getId()));
        assertTrue(cero.contains(sinCalificar.getId()));
    }

    @Test
    @DisplayName("BUSQUEDA: rating=5 es exacto (un 4.75 queda en 4★, no en 5★)")
    void cincoEsExacto() {
        String sufijo = uniqueSuffix();
        String autor = autorDe(sufijo);
        Review casi = create("Casi " + sufijo, sufijo, "4.75");
        Review cinco = create("Cinco " + sufijo, sufijo, "5");

        assertEquals(Set.of(cinco.getId()),
                ids(reviewService.searchReviews(autor, null, 5, null, null, null, null)));
        assertEquals(Set.of(casi.getId()),
                ids(reviewService.searchReviews(autor, null, 4, null, null, null, null)));
    }

    @Test
    @DisplayName("BUSQUEDA: rating fuera de 0..5 da 400")
    void busquedaFueraDeRangoDa400() {
        for (int invalido : new int[]{-1, 6}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> reviewService.searchReviews(null, null, invalido, null, null, null, null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason().contains("0 al 5"), ex.getReason());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // JSON
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON: el rating sale como NUMERO (3.25, 4.00), nunca string ni 4E+0")
    void ratingSaleComoNumero() throws Exception {
        String sufijo = uniqueSuffix();
        Review created = create("Json " + sufijo, sufijo, "3.25");

        assertEquals("{\"rating\":3.25}",
                objectMapper.writeValueAsString(Map.of("rating", created.getRating())));
        assertEquals("{\"rating\":4.00}",
                objectMapper.writeValueAsString(Map.of("rating", new BigDecimal("4").setScale(2))));
        // Un BigDecimal con exponente (4E+0) tiene que salir plano igual.
        assertEquals("{\"rating\":4}",
                objectMapper.writeValueAsString(Map.of("rating", new BigDecimal("4E+0"))));
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Autor unico por test: acota las busquedas a lo que creo este test. */
    private String autorDe(String sufijo) {
        return "Autor Rating " + sufijo;
    }

    private NewReviewRequest base(String titulo, String sufijo, String rating) {
        NewReviewRequest req = new NewReviewRequest();
        req.setTitle(titulo);
        req.setAuthor(autorDe(sufijo));
        req.setRating(new BigDecimal(rating));
        req.setReviewText("resenia de prueba");
        return req;
    }

    private Review create(String titulo, String sufijo, String rating) {
        return track(reviewService.createReviewForNewBook(base(PREFIX + titulo, sufijo, rating)));
    }

    /** Agenda el libro creado para el cleanup del @AfterEach. */
    private Review track(Review review) {
        if (review != null && review.getBook() != null && review.getBook().getId() != null) {
            createdBookIds.add(review.getBook().getId());
        }
        return review;
    }

    private Set<Long> ids(List<Review> reviews) {
        return reviews.stream().map(Review::getId).collect(Collectors.toSet());
    }

    private BigDecimal ratingOf(Long reviewId) {
        return jdbc.queryForObject("SELECT rating FROM reviews WHERE id = ?", BigDecimal.class, reviewId);
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? -1 : n;
    }
}
