package net.casapipis.camireads.service;

import net.casapipis.camireads.dto.saga.BookSaga;
import net.casapipis.camireads.dto.saga.NewSagaRequest;
import net.casapipis.camireads.dto.saga.SagaBook;
import net.casapipis.camireads.dto.saga.SagaDetail;
import net.casapipis.camireads.dto.saga.SagaSummary;
import net.casapipis.camireads.dto.saga.UpdateSagaRequest;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mis sagas (Fase 10) contra la base REAL de sandbox (127.0.0.1:5434). NUNCA
 * produccion. Se saltean solos si no hay datasource configurado.
 *
 * Los libros que se meten en sagas los crea el propio test (con prefijo
 * "ZZ Test Saga"), asi ningun libro de Camila entra a una saga ni se toca.
 * En el @AfterEach se borra todo lo creado y se verifica que books, reviews,
 * book_tags, sagas y saga_books vuelvan al conteo con el que arrancaron.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class SagaServiceDbTest {

    private static final String PREFIX = "ZZ Test Saga ";

    private static final long ID_INEXISTENTE = 999999L;

    /** 1x1 png valido. */
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired
    private SagaService sagaService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdBookIds = new ArrayList<>();
    private final List<Long> createdSagaIds = new ArrayList<>();

    private long booksBefore;
    private long reviewsBefore;
    private BigDecimal ratingSumBefore;
    private long bookTagsBefore;
    private long sagasBefore;
    private long sagaBooksBefore;

    @BeforeEach
    void contar() {
        booksBefore = count("books");
        reviewsBefore = count("reviews");
        ratingSumBefore = ratingSum();
        bookTagsBefore = count("book_tags");
        sagasBefore = count("sagas");
        sagaBooksBefore = count("saga_books");
        assertTrue(booksBefore > 0, "la base de prueba tiene que tener libros");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM books WHERE id = ?", Long.class, ID_INEXISTENTE));
    }

    @AfterEach
    void limpiar() {
        for (Long id : createdSagaIds) {
            jdbc.update("DELETE FROM sagas WHERE id = ?", id);
        }
        createdSagaIds.clear();
        for (Long id : createdBookIds) {
            // reviews y saga_books se van por CASCADE desde books.
            jdbc.update("DELETE FROM books WHERE id = ?", id);
        }
        createdBookIds.clear();

        assertEquals(booksBefore, count("books"), "el test tiene que dejar books como la encontro");
        assertEquals(reviewsBefore, count("reviews"), "el test tiene que dejar reviews como la encontro");
        assertEquals(0, ratingSumBefore.compareTo(ratingSum()), "cambio la suma de ratings");
        assertEquals(bookTagsBefore, count("book_tags"), "el test no tiene que tocar book_tags");
        assertEquals(sagasBefore, count("sagas"), "quedaron sagas del test");
        assertEquals(sagaBooksBefore, count("saga_books"), "quedaron filas en saga_books");
    }

    // ─────────────────────────────────────────────────────────────
    // Alta / renombre / imagen
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Alta: 409 si el slug choca, con acentos y mayusculas (Fénix vs fenix)")
    void altaConSlugRepetido() {
        String suf = suffix();
        SagaDetail fenix = track(sagaService.create(req(PREFIX + "Fénix " + suf, null, null)));
        assertEquals(PREFIX + "Fénix " + suf, fenix.name());
        assertEquals(0, fenix.bookCount());
        assertTrue(fenix.books().isEmpty());
        assertTrue(fenix.previewCovers().isEmpty());

        ResponseStatusException e = status(HttpStatus.CONFLICT,
                () -> sagaService.create(req("zz test saga FENIX " + suf.toUpperCase(), null, null)));
        assertTrue(e.getReason().contains("Ya tenés una saga que se llama «" + fenix.name() + "»"),
                e.getReason());
    }

    @Test
    @DisplayName("Renombrar: 409 si choca con OTRA saga; cambiar solo mayusculas/acentos de la propia anda")
    void renombrar() {
        String suf = suffix();
        SagaDetail a = track(sagaService.create(req(PREFIX + "Dragon " + suf, null, null)));
        SagaDetail b = track(sagaService.create(req(PREFIX + "Otra " + suf, null, null)));

        UpdateSagaRequest choca = new UpdateSagaRequest();
        choca.setName(PREFIX + "Dragón " + suf);
        status(HttpStatus.CONFLICT, () -> sagaService.update(b.id(), choca));
        assertEquals(PREFIX + "Otra " + suf, sagaService.get(b.id()).name(), "el 409 no renombra");

        UpdateSagaRequest propia = new UpdateSagaRequest();
        propia.setName(PREFIX + "Dragón " + suf);
        assertEquals(PREFIX + "Dragón " + suf, sagaService.update(a.id(), propia).name());

        status(HttpStatus.NOT_FOUND, () -> sagaService.update(ID_INEXISTENTE, propia));
    }

    @Test
    @DisplayName("Nombre vacio o > 120 -> 400")
    void nombreInvalido() {
        status(HttpStatus.BAD_REQUEST, () -> sagaService.create(req("   ", null, null)));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.create(req(null, null, null)));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.create(req(PREFIX + "x".repeat(121), null, null)));
    }

    @Test
    @DisplayName("Imagen: link y foto a la vez -> 400; foto > tope -> 400; mime raro -> 400; mal formada -> 400")
    void imagenInvalida() {
        String suf = suffix();
        String okData = "data:image/png;base64," + PNG_B64;

        status(HttpStatus.BAD_REQUEST,
                () -> sagaService.create(req(PREFIX + "Ambas " + suf, "https://x/y.jpg", okData)));

        String grande = "data:image/jpeg;base64," + "A".repeat(SagaService.MAX_COVER_B64_CHARS + 4);
        status(HttpStatus.BAD_REQUEST, () -> sagaService.create(req(PREFIX + "Grande " + suf, null, grande)));

        status(HttpStatus.BAD_REQUEST,
                () -> sagaService.create(req(PREFIX + "Gif " + suf, null, "data:image/gif;base64," + PNG_B64)));
        status(HttpStatus.BAD_REQUEST,
                () -> sagaService.create(req(PREFIX + "Pelada " + suf, null, PNG_B64)));
        status(HttpStatus.BAD_REQUEST,
                () -> sagaService.create(req(PREFIX + "Rota " + suf, null, "data:image/png;base64,@@@")));

        assertEquals(sagasBefore, count("sagas"), "ningun 400 deja una saga creada");
    }

    @Test
    @DisplayName("Imagen: foto -> data URL; link borra foto; clearCover borra todo; ausente no toca")
    void imagenCiclo() {
        String suf = suffix();
        SagaDetail s = track(sagaService.create(
                req(PREFIX + "Foto " + suf, null, "data:image/png;base64," + PNG_B64)));
        assertEquals("data:image/png;base64," + PNG_B64, s.coverDataUrl());
        assertNull(s.urlCover());

        UpdateSagaRequest soloNombre = new UpdateSagaRequest();
        soloNombre.setName(PREFIX + "Foto2 " + suf);
        SagaDetail renombrada = sagaService.update(s.id(), soloNombre);
        assertEquals("data:image/png;base64," + PNG_B64, renombrada.coverDataUrl(), "renombrar no toca la foto");

        UpdateSagaRequest link = new UpdateSagaRequest();
        link.setUrlCover("https://example.com/tapa.jpg");
        SagaDetail conLink = sagaService.update(s.id(), link);
        assertEquals("https://example.com/tapa.jpg", conLink.urlCover());
        assertNull(conLink.coverDataUrl(), "mandar link borra la foto");

        UpdateSagaRequest foto = new UpdateSagaRequest();
        foto.setCoverDataUrl("data:image/png;base64," + PNG_B64);
        SagaDetail conFoto = sagaService.update(s.id(), foto);
        assertNull(conFoto.urlCover(), "mandar foto borra el link");
        assertNotNull(conFoto.coverDataUrl());

        UpdateSagaRequest contradictorio = new UpdateSagaRequest();
        contradictorio.setClearCover(true);
        contradictorio.setUrlCover("https://example.com/otra.jpg");
        status(HttpStatus.BAD_REQUEST, () -> sagaService.update(s.id(), contradictorio));

        UpdateSagaRequest clear = new UpdateSagaRequest();
        clear.setClearCover(true);
        SagaDetail limpia = sagaService.update(s.id(), clear);
        assertNull(limpia.urlCover());
        assertNull(limpia.coverDataUrl());
    }

    // ─────────────────────────────────────────────────────────────
    // Libros: agregar / reordenar / quitar
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Agregar 3 libros -> 1,2,3; repetido -> 409; inexistentes -> 404; rating 0 sin resenia")
    void agregarLibros() {
        String suf = suffix();
        long a = book(suf, "A", "https://example.com/a.jpg", null);
        long b = book(suf, "B", null, PNG_B64);
        long c = book(suf, "C", null, null);
        review(a, "3.75");
        jdbc.update("UPDATE books SET end_read_date = DATE '2025-03-14' WHERE id = ?", a);

        SagaDetail s = track(sagaService.create(req(PREFIX + "Libros " + suf, null, null)));
        sagaService.addBook(s.id(), a);
        sagaService.addBook(s.id(), b);
        SagaDetail d = sagaService.addBook(s.id(), c);

        assertEquals(List.of(a, b, c), ids(d));
        assertEquals(List.of(1, 2, 3), positions(d));
        assertEquals(3, d.bookCount());

        SagaBook ba = d.books().get(0);
        assertEquals(0, new BigDecimal("3.75").compareTo(ba.rating()));
        assertEquals(LocalDate.of(2025, 3, 14), ba.endReadDate());
        assertEquals("https://example.com/a.jpg", ba.urlCover());
        assertEquals("data:image/jpeg;base64," + PNG_B64, d.books().get(1).urlCover());
        assertNull(d.books().get(2).urlCover());
        assertEquals(0, BigDecimal.ZERO.compareTo(d.books().get(2).rating()), "sin resenia -> 0");
        assertNull(d.books().get(2).endReadDate());

        status(HttpStatus.CONFLICT, () -> sagaService.addBook(s.id(), b));
        status(HttpStatus.NOT_FOUND, () -> sagaService.addBook(s.id(), ID_INEXISTENTE));
        status(HttpStatus.NOT_FOUND, () -> sagaService.addBook(ID_INEXISTENTE, a));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.addBook(s.id(), null));
        assertEquals(List.of(1, 2, 3), positions(sagaService.get(s.id())), "los errores no tocan nada");
    }

    @Test
    @DisplayName("Reordenar: permutacion valida -> orden nuevo; faltan/sobran/repetidos -> 400 y orden intacto")
    void reordenar() {
        String suf = suffix();
        long a = book(suf, "A", null, null);
        long b = book(suf, "B", null, null);
        long c = book(suf, "C", null, null);
        long ajeno = book(suf, "Ajeno", null, null);
        SagaDetail s = track(sagaService.create(req(PREFIX + "Orden " + suf, null, null)));
        sagaService.addBook(s.id(), a);
        sagaService.addBook(s.id(), b);
        sagaService.addBook(s.id(), c);

        SagaDetail r = sagaService.reorder(s.id(), List.of(c, a, b));
        assertEquals(List.of(c, a, b), ids(r));
        assertEquals(List.of(1, 2, 3), positions(r));

        // Invertir del todo: todas las posiciones chocan en el medio (DEFERRABLE).
        assertEquals(List.of(b, a, c), ids(sagaService.reorder(s.id(), List.of(b, a, c))));

        List<Long> esperado = List.of(b, a, c);
        status(HttpStatus.BAD_REQUEST, () -> sagaService.reorder(s.id(), List.of(a, b)));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.reorder(s.id(), List.of(a, b, c, ajeno)));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.reorder(s.id(), List.of(a, a, b)));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.reorder(s.id(), List.of(a, b, ajeno)));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.reorder(s.id(), null));
        status(HttpStatus.NOT_FOUND, () -> sagaService.reorder(ID_INEXISTENTE, List.of()));

        SagaDetail intacta = sagaService.get(s.id());
        assertEquals(esperado, ids(intacta), "un 400 no toca el orden");
        assertEquals(List.of(1, 2, 3), positions(intacta));
    }

    @Test
    @DisplayName("Quitar el del medio renumera 1..n; quitar uno que no esta -> 404")
    void quitarDelMedio() {
        String suf = suffix();
        long a = book(suf, "A", null, null);
        long b = book(suf, "B", null, null);
        long c = book(suf, "C", null, null);
        long d = book(suf, "D", null, null);
        SagaDetail s = track(sagaService.create(req(PREFIX + "Quitar " + suf, null, null)));
        for (long id : List.of(a, b, c, d)) {
            sagaService.addBook(s.id(), id);
        }

        SagaDetail r = sagaService.removeBook(s.id(), b);
        assertEquals(List.of(a, c, d), ids(r));
        assertEquals(List.of(1, 2, 3), positions(r));

        status(HttpStatus.NOT_FOUND, () -> sagaService.removeBook(s.id(), b));
        status(HttpStatus.NOT_FOUND, () -> sagaService.removeBook(ID_INEXISTENTE, a));

        // Despues de renumerar, agregar va al final (4), sin choques.
        assertEquals(List.of(1, 2, 3, 4), positions(sagaService.addBook(s.id(), b)));
    }

    @Test
    @DisplayName("Borrar la saga NO borra libros; borrar un libro lo saca de la saga")
    void borrados() {
        String suf = suffix();
        long a = book(suf, "A", null, null);
        long b = book(suf, "B", null, null);
        long c = book(suf, "C", null, null);
        SagaDetail s1 = track(sagaService.create(req(PREFIX + "Borrar1 " + suf, null, null)));
        SagaDetail s2 = track(sagaService.create(req(PREFIX + "Borrar2 " + suf, null, null)));
        sagaService.addBook(s1.id(), a);
        sagaService.addBook(s1.id(), b);
        sagaService.addBook(s2.id(), a);
        sagaService.addBook(s2.id(), b);
        sagaService.addBook(s2.id(), c);

        long booksConLosMios = count("books");
        sagaService.delete(s1.id());
        assertEquals(booksConLosMios, count("books"), "¡BORRAR LA SAGA BORRO LIBROS!");
        status(HttpStatus.NOT_FOUND, () -> sagaService.get(s1.id()));
        status(HttpStatus.NOT_FOUND, () -> sagaService.delete(s1.id()));
        assertEquals(3, sagaService.get(s2.id()).bookCount(), "la otra saga ni se entero");

        // Borrar un libro (del test) lo saca de la saga por CASCADE.
        jdbc.update("DELETE FROM books WHERE id = ?", b);
        createdBookIds.remove(Long.valueOf(b));
        SagaDetail d = sagaService.get(s2.id());
        assertEquals(List.of(a, c), ids(d));
        assertEquals(2, d.bookCount());
    }

    @Test
    @DisplayName("GET /books/{id}/sagas refleja altas, posiciones, reordenes y bajas")
    void sagasDeUnLibro() {
        String suf = suffix();
        long a = book(suf, "A", null, null);
        long b = book(suf, "B", null, null);
        SagaDetail s1 = track(sagaService.create(req(PREFIX + "Uno " + suf, null, null)));
        SagaDetail s2 = track(sagaService.create(req(PREFIX + "Dos " + suf, null, null)));

        assertTrue(sagaService.sagasOfBook(b).isEmpty());
        status(HttpStatus.NOT_FOUND, () -> sagaService.sagasOfBook(ID_INEXISTENTE));

        sagaService.addBook(s1.id(), a);
        sagaService.addBook(s1.id(), b);
        sagaService.addBook(s2.id(), b);

        List<BookSaga> deB = sagaService.sagasOfBook(b);
        assertEquals(2, deB.size());
        BookSaga enUno = find(deB, s1.id());
        assertEquals(2, enUno.position());
        assertEquals(2, enUno.bookCount());
        assertEquals(PREFIX + "Uno " + suf, enUno.name());
        assertEquals(1, find(deB, s2.id()).position());

        sagaService.reorder(s1.id(), List.of(b, a));
        assertEquals(1, find(sagaService.sagasOfBook(b), s1.id()).position());

        sagaService.removeBook(s2.id(), b);
        assertEquals(List.of(s1.id()), sagaService.sagasOfBook(b).stream().map(BookSaga::id).toList());

        sagaService.delete(s1.id());
        assertTrue(sagaService.sagasOfBook(b).isEmpty());
    }

    @Test
    @DisplayName("Listado: previewCovers max 4, en orden, salteando libros sin tapa; updatedAt desc")
    void listadoPreviewCovers() {
        String suf = suffix();
        long sinTapa = book(suf, "SinTapa", null, null);
        long l1 = book(suf, "1", "https://example.com/1.jpg", null);
        long l2 = book(suf, "2", null, PNG_B64);
        long l3 = book(suf, "3", "https://example.com/3.jpg", PNG_B64);   // link gana
        long l4 = book(suf, "4", "  ", PNG_B64);                          // link en blanco -> b64
        long l5 = book(suf, "5", "https://example.com/5.jpg", null);

        SagaDetail vieja = track(sagaService.create(req(PREFIX + "Vieja " + suf, null, null)));
        SagaDetail s = track(sagaService.create(req(PREFIX + "Collage " + suf, null, null)));
        for (long id : List.of(l5, sinTapa, l1, l2, l3, l4)) {
            sagaService.addBook(s.id(), id);
        }
        // Reordenar: 1,sinTapa,2,3,4,5
        sagaService.reorder(s.id(), List.of(l1, sinTapa, l2, l3, l4, l5));

        List<String> esperadas = List.of(
                "https://example.com/1.jpg",
                "data:image/jpeg;base64," + PNG_B64,
                "https://example.com/3.jpg",
                "data:image/jpeg;base64," + PNG_B64);

        List<SagaSummary> lista = sagaService.list();
        SagaSummary collage = lista.stream().filter(x -> x.id().equals(s.id())).findFirst().orElseThrow();
        assertEquals(esperadas, collage.previewCovers());
        assertEquals(6, collage.bookCount());
        assertNull(collage.coverDataUrl());

        SagaSummary vacia = lista.stream().filter(x -> x.id().equals(vieja.id())).findFirst().orElseThrow();
        assertTrue(vacia.previewCovers().isEmpty());
        assertEquals(0, vacia.bookCount());

        // La que se toco ultima va antes.
        assertTrue(indexOf(lista, s.id()) < indexOf(lista, vieja.id()), "orden por updated_at desc");

        // El detalle arma el mismo collage.
        assertEquals(esperadas, sagaService.get(s.id()).previewCovers());
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private NewSagaRequest req(String name, String url, String dataUrl) {
        NewSagaRequest r = new NewSagaRequest();
        r.setName(name);
        r.setUrlCover(url);
        r.setCoverDataUrl(dataUrl);
        return r;
    }

    private SagaDetail track(SagaDetail s) {
        createdSagaIds.add(s.id());
        return s;
    }

    private long book(String suf, String name, String url, String b64) {
        Long id = jdbc.queryForObject("""
                INSERT INTO books (title, author, has_url_cover, url_cover, b64_cover)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                PREFIX + name + " " + suf, "ZZ Autora Test", url != null && !url.isBlank(), url, b64);
        createdBookIds.add(id);
        return id;
    }

    private void review(long bookId, String rating) {
        jdbc.update("INSERT INTO reviews (book_id, rating, review_text) VALUES (?, ?, '')",
                bookId, new BigDecimal(rating));
    }

    private static List<Long> ids(SagaDetail d) {
        return d.books().stream().map(SagaBook::bookId).toList();
    }

    private static List<Integer> positions(SagaDetail d) {
        return d.books().stream().map(SagaBook::position).toList();
    }

    private static BookSaga find(List<BookSaga> list, Long sagaId) {
        return list.stream().filter(x -> x.id().equals(sagaId)).findFirst().orElseThrow();
    }

    private static int indexOf(List<SagaSummary> list, Long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static ResponseStatusException status(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, e.getStatusCode(), e.getReason());
        return e;
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? -1 : n;
    }

    private BigDecimal ratingSum() {
        BigDecimal s = jdbc.queryForObject("SELECT COALESCE(sum(rating), 0) FROM reviews", BigDecimal.class);
        return s == null ? BigDecimal.ZERO : s;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
