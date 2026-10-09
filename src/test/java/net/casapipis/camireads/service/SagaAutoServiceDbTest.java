package net.casapipis.camireads.service;

import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.dto.NewReviewRequest;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import net.casapipis.camireads.dto.saga.AutoSagaApplyResult;
import net.casapipis.camireads.dto.saga.AutoSagaPreview;
import net.casapipis.camireads.dto.saga.NewSagaRequest;
import net.casapipis.camireads.dto.saga.SagaBook;
import net.casapipis.camireads.dto.saga.SagaDetail;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Armado automatico de sagas (Fase 10b) contra la base REAL de sandbox
 * (127.0.0.1:5434). NUNCA produccion. Se saltean solos sin datasource.
 *
 * COMO SE PRUEBA SIN CREAR LAS ~300 SAGAS REALES: el armado recorre toda la
 * biblioteca. Los tests de comportamiento usan las variantes package-private
 * de SagaAutoService (preview/apply/undo con filtro de claves), que corren
 * EXACTAMENTE el mismo codigo que los endpoints pero solo sobre las claves de
 * los libros ficticios del test ("ZZ Test Auto ... (ZZ Serie Uno <suf>, #N)").
 * Cada test usa un sufijo unico, asi ni siquiera se pisan entre ellos.
 *
 * Aparte, dos pruebas recorren la biblioteca ENTERA: el preview real (solo
 * lectura) y el apply real dentro de una transaccion que se tira al final
 * (rollback), para medir el tiempo y probar que no revienta con datos reales
 * sin dejar ni una saga.
 *
 * El @AfterEach borra todo lo creado y verifica la huella completa: books,
 * reviews, suma de rating, book_tags, sagas, saga_books, saga_series_keys y
 * saga_book_dismissed como estaban.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class SagaAutoServiceDbTest {

    private static final String PREFIX = "ZZ Test Auto ";
    private static final long ID_INEXISTENTE = 999999L;

    @Autowired
    private SagaAutoService autoService;

    @Autowired
    private SagaService sagaService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private final List<Long> createdBookIds = new ArrayList<>();
    private final Set<Long> createdSagaIds = new LinkedHashSet<>();

    /** Sufijo unico del test: va en el nombre de sus sagas y por ende en sus claves. */
    private String suf;

    private long booksBefore;
    private long reviewsBefore;
    private BigDecimal ratingSumBefore;
    private long bookTagsBefore;
    private long sagasBefore;
    private long sagaBooksBefore;
    private long keysBefore;
    private long dismissedBefore;

    @BeforeEach
    void contar() {
        suf = UUID.randomUUID().toString().substring(0, 8);
        booksBefore = count("books");
        reviewsBefore = count("reviews");
        ratingSumBefore = ratingSum();
        bookTagsBefore = count("book_tags");
        sagasBefore = count("sagas");
        sagaBooksBefore = count("saga_books");
        keysBefore = count("saga_series_keys");
        dismissedBefore = count("saga_book_dismissed");
        assertTrue(booksBefore > 0, "la base de prueba tiene que tener libros");
    }

    @AfterEach
    void limpiar() {
        String like = "zz-%" + suf;
        // Sagas del test: las registradas por sus claves, las que tienen su
        // slug (aunque se hayan renombrado se trackean aparte) y las trackeadas.
        createdSagaIds.addAll(jdbc.queryForList(
                "SELECT saga_id FROM saga_series_keys WHERE series_key LIKE ? AND saga_id IS NOT NULL",
                Long.class, like));
        createdSagaIds.addAll(jdbc.queryForList("SELECT id FROM sagas WHERE slug LIKE ?", Long.class, like));
        jdbc.update("DELETE FROM saga_series_keys WHERE series_key LIKE ?", like);
        for (Long id : createdSagaIds) {
            jdbc.update("DELETE FROM saga_series_keys WHERE saga_id = ?", id);
            jdbc.update("DELETE FROM sagas WHERE id = ?", id);
        }
        createdSagaIds.clear();
        for (Long id : createdBookIds) {
            // reviews, review_quotes, saga_books y saga_book_dismissed caen por CASCADE.
            jdbc.update("DELETE FROM review_quotes WHERE review_id IN (SELECT id FROM reviews WHERE book_id = ?)", id);
            jdbc.update("DELETE FROM reviews WHERE book_id = ?", id);
            jdbc.update("DELETE FROM books WHERE id = ?", id);
        }
        createdBookIds.clear();

        assertEquals(booksBefore, count("books"), "books");
        assertEquals(reviewsBefore, count("reviews"), "reviews");
        assertEquals(0, ratingSumBefore.compareTo(ratingSum()), "suma de ratings");
        assertEquals(bookTagsBefore, count("book_tags"), "book_tags");
        assertEquals(sagasBefore, count("sagas"), "quedaron sagas del test");
        assertEquals(sagaBooksBefore, count("saga_books"), "saga_books");
        assertEquals(keysBefore, count("saga_series_keys"), "saga_series_keys");
        assertEquals(dismissedBefore, count("saga_book_dismissed"), "saga_book_dismissed");
    }

    // ─────────────────────────────────────────────────────────────
    // Crear / no duplicar
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Crea con 2+ libros (orden de tomo, nombre del menor tomo); con 1 libro no crea")
    void crearConDosNoConUno() {
        String uno = serie("Uno");
        long t2 = book("B", "zz serie uno " + suf, " #2");          // otro casing, sin coma
        long t1 = book("A", uno, ", #1");
        long t15 = book("C", uno, ", #1.5");
        book("Solo", serie("Dos"), ", #1");                          // 1 libro: no se crea

        AutoSagaPreview p = autoService.preview(mine());
        assertEquals(1, p.create().size(), "solo la serie con 2+ libros");
        assertTrue(p.extend().isEmpty());
        AutoSagaPreview.Create c = p.create().get(0);
        assertEquals(uno, c.name(), "nombre como lo escribe el libro de menor tomo");
        assertEquals(3, c.bookCount());
        assertEquals(List.of(t1, t15, t2), c.books().stream().map(AutoSagaPreview.Book::bookId).toList());
        assertEquals(3, p.totalBooks());
        assertEquals(0, p.skippedDismissed());

        AutoSagaApplyResult r = autoService.apply(mine());
        assertEquals(1, r.created());
        assertEquals(0, r.extended());
        assertEquals(3, r.booksAdded());
        assertTrue(r.skippedConflicts().isEmpty());

        SagaDetail s = sagaService.get(sagaOfKey(key("Uno")));
        assertEquals(uno, s.name());
        assertTrue(s.autoDetected());
        assertEquals(List.of(t1, t15, t2), ids(s));
        assertEquals(List.of(1, 2, 3), positions(s));
        assertTrue(sagaService.list().stream().anyMatch(x -> x.id().equals(s.id()) && x.autoDetected()),
                "el listado marca autoDetected");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM saga_series_keys WHERE series_key = ?", Long.class, key("Dos")),
                "la serie de 1 libro ni se registra");
    }

    @Test
    @DisplayName("Re-ejecutar no duplica; un libro nuevo de la serie se suma al final y sigue siendo automatica")
    void reEjecutarNoDuplica() {
        String uno = serie("Uno");
        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));

        AutoSagaApplyResult otra = autoService.apply(mine());
        assertEquals(0, otra.created());
        assertEquals(0, otra.extended());
        assertEquals(0, otra.booksAdded());
        AutoSagaPreview vacio = autoService.preview(mine());
        assertTrue(vacio.create().isEmpty() && vacio.extend().isEmpty());
        assertEquals(0, vacio.totalBooks());

        long c = book("C", uno, ", #3");
        long medio = book("Medio", uno, ", #0.5");   // tomo menor pero llega despues: va al final igual
        AutoSagaPreview p = autoService.preview(mine());
        assertEquals(1, p.extend().size());
        assertEquals(sagaId, p.extend().get(0).sagaId());
        assertEquals(2, p.extend().get(0).addCount());

        AutoSagaApplyResult r = autoService.apply(mine());
        assertEquals(0, r.created());
        assertEquals(1, r.extended());
        assertEquals(2, r.booksAdded());
        SagaDetail s = sagaService.get(sagaId);
        assertEquals(List.of(a, b, medio, c), ids(s), "se agregan AL FINAL, en orden de tomo");
        assertEquals(List.of(1, 2, 3, 4), positions(s));
        assertTrue(s.autoDetected(), "extender no la vuelve 'de Camila'");
    }

    // ─────────────────────────────────────────────────────────────
    // Respeta lo que hizo Camila
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Renombrar la saga y re-ejecutar no crea otra: extiende la renombrada")
    void renombrarNoCreaOtra() {
        String uno = serie("Uno");
        book("A", uno, ", #1");
        book("B", uno, ", #2");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));

        UpdateSagaRequest ren = new UpdateSagaRequest();
        ren.setName("ZZ Otro Nombre " + suf);
        SagaDetail renamed = sagaService.update(sagaId, ren);
        assertFalse(renamed.autoDetected(), "renombrar la vuelve de Camila");

        long c = book("C", uno, ", #3");
        AutoSagaApplyResult r = autoService.apply(mine());
        assertEquals(0, r.created(), "no crea otra saga con el nombre del titulo");
        assertEquals(1, r.extended());
        SagaDetail s = sagaService.get(sagaId);
        assertEquals(c, ids(s).get(2));
        assertEquals("ZZ Otro Nombre " + suf, s.name());
    }

    @Test
    @DisplayName("Quitar un libro lo descarta: re-ejecutar no lo re-agrega; agregarlo a mano borra el descarte")
    void quitarNoReagrega() {
        String uno = serie("Uno");
        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");
        long c = book("C", uno, ", #3");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));

        SagaDetail sin = sagaService.removeBook(sagaId, b);
        assertFalse(sin.autoDetected(), "sacar un libro la vuelve de Camila");
        assertEquals(1, dismissedCount(sagaId, b));

        AutoSagaPreview p = autoService.preview(mine());
        assertTrue(p.extend().isEmpty(), "el descartado no aparece");
        assertEquals(0, autoService.apply(mine()).booksAdded());
        assertEquals(List.of(a, c), ids(sagaService.get(sagaId)));

        sagaService.addBook(sagaId, b);
        assertEquals(0, dismissedCount(sagaId, b), "agregarlo a mano revierte el descarte");
    }

    @Test
    @DisplayName("Borrar la saga deja la clave descartada: re-ejecutar no la recrea")
    void borrarNoRecrea() {
        String uno = serie("Uno");
        book("A", uno, ", #1");
        book("B", uno, ", #2");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));

        sagaService.delete(sagaId);
        assertNull(jdbc.queryForObject(
                "SELECT saga_id FROM saga_series_keys WHERE series_key = ?", Long.class, key("Uno")),
                "el FK SET NULL la marca descartada");

        AutoSagaPreview p = autoService.preview(mine());
        assertTrue(p.create().isEmpty());
        assertEquals(1, p.skippedDismissed());
        assertEquals(0, autoService.apply(mine()).created());
    }

    @Test
    @DisplayName("Saga armada a mano con el mismo nombre: se registra la clave y se extiende (no se crea otra)")
    void manualMismoNombreSeExtiende() {
        String uno = serie("Uno");
        long ajeno = book("Ajeno", "nada", null);
        SagaDetail manual = sagaService.create(req("zz SERIE uno " + suf));   // mismo slug, otra escritura
        createdSagaIds.add(manual.id());
        sagaService.addBook(manual.id(), ajeno);

        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");

        AutoSagaPreview p = autoService.preview(mine());
        assertTrue(p.create().isEmpty());
        assertEquals(1, p.extend().size());
        assertEquals(manual.id(), p.extend().get(0).sagaId());

        AutoSagaApplyResult r = autoService.apply(mine());
        assertEquals(0, r.created());
        assertEquals(1, r.extended());
        assertEquals(manual.id(), sagaOfKey(key("Uno")), "la clave queda apuntando a la manual");
        SagaDetail s = sagaService.get(manual.id());
        assertEquals(List.of(ajeno, a, b), ids(s));
        assertFalse(s.autoDetected(), "una saga manual sigue siendo manual");
    }

    // ─────────────────────────────────────────────────────────────
    // Deshacer
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deshacer borra solo las automaticas sin editar, borra sus claves y permite re-crearlas")
    void deshacer() {
        String uno = serie("Uno");
        String dos = serie("Dos");
        book("A", uno, ", #1");
        book("B", uno, ", #2");
        book("C", dos, ", #1");
        book("D", dos, ", #2");
        int autoAntes = autoService.undoPreview().count();

        assertEquals(2, autoService.apply(mine()).created());
        long sUno = sagaOfKey(key("Uno"));
        long sDos = sagaOfKey(key("Dos"));
        assertEquals(autoAntes + 2, autoService.undoPreview().count());

        UpdateSagaRequest ren = new UpdateSagaRequest();
        ren.setName(dos + " editada");
        sagaService.update(sDos, ren);
        assertEquals(autoAntes + 1, autoService.undoPreview().count());

        assertEquals(1, autoService.undo(List.of(sUno, sDos)));
        assertThrows(ResponseStatusException.class, () -> sagaService.get(sUno));
        assertEquals(2, sagaService.get(sDos).bookCount(), "la editada no se toca");
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM saga_series_keys WHERE series_key = ?", Long.class, key("Uno")),
                "deshacer BORRA la clave (no la deja descartada)");
        assertEquals(sDos, sagaOfKey(key("Dos")));

        AutoSagaApplyResult r = autoService.apply(mine());
        assertEquals(1, r.created(), "despues de deshacer se puede volver a armar");
        assertEquals(0, r.extended());
        assertTrue(sagaService.get(sagaOfKey(key("Uno"))).autoDetected());
    }

    // ─────────────────────────────────────────────────────────────
    // Unir
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unir: libros al final sin repetir, claves y descartes pasan a target, from se borra, target es de Camila")
    void unir() {
        String uno = serie("Uno");
        String unos = serie("Unos");            // Goodreads lo escribio distinto
        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");
        long c = book("C", unos, ", #3");
        long d = book("D", unos, ", #4");
        long e = book("E", unos, ", #5");
        assertEquals(2, autoService.apply(mine()).created());
        long target = sagaOfKey(key("Uno"));
        long from = sagaOfKey(key("Unos"));

        // Un libro repetido en las dos, y uno descartado de from.
        sagaService.addBook(from, a);
        sagaService.removeBook(from, e);
        assertEquals(1, dismissedCount(from, e));

        SagaDetail m = sagaService.merge(target, from);
        assertEquals(List.of(a, b, c, d), ids(m), "los de from al final, en su orden, sin repetir a");
        assertEquals(List.of(1, 2, 3, 4), positions(m));
        assertFalse(m.autoDetected());
        assertThrows(ResponseStatusException.class, () -> sagaService.get(from));
        assertEquals(target, sagaOfKey(key("Unos")), "la clave de from NO quedo descartada: apunta a target");
        assertEquals(1, dismissedCount(target, e), "el descarte se mudo");

        // Re-ejecutar: no recrea "Unos" y no mete el descartado.
        AutoSagaApplyResult r = autoService.apply(mine());
        assertEquals(0, r.created());
        assertEquals(0, r.booksAdded());

        // Un libro nuevo de cualquiera de las dos claves va a target.
        long f = book("F", unos, ", #6");
        assertEquals(1, autoService.apply(mine()).booksAdded());
        assertEquals(f, ids(sagaService.get(target)).get(4));
    }

    @Test
    @DisplayName("Unir: misma saga o sin fromSagaId -> 400; inexistentes -> 404")
    void unirErrores() {
        SagaDetail s = sagaService.create(req("ZZ Merge Err " + suf));
        createdSagaIds.add(s.id());
        status(HttpStatus.BAD_REQUEST, () -> sagaService.merge(s.id(), s.id()));
        status(HttpStatus.BAD_REQUEST, () -> sagaService.merge(s.id(), null));
        status(HttpStatus.NOT_FOUND, () -> sagaService.merge(s.id(), ID_INEXISTENTE));
        status(HttpStatus.NOT_FOUND, () -> sagaService.merge(ID_INEXISTENTE, s.id()));
        assertNotNull(sagaService.get(s.id()), "los errores no borran nada");
    }

    @Test
    @DisplayName("Reordenar y cambiar imagen tambien la vuelven de Camila")
    void edicionesApaganAuto() {
        String uno = serie("Uno");
        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));
        assertFalse(sagaService.reorder(sagaId, List.of(b, a)).autoDetected());

        String dos = serie("Dos");
        book("C", dos, ", #1");
        book("D", dos, ", #2");
        autoService.apply(mine());
        long s2 = sagaOfKey(key("Dos"));
        assertTrue(sagaService.get(s2).autoDetected());
        UpdateSagaRequest img = new UpdateSagaRequest();
        img.setUrlCover("https://example.com/x.jpg");
        assertFalse(sagaService.update(s2, img).autoDetected());
    }

    // ─────────────────────────────────────────────────────────────
    // Libro nuevo / renombrado (ReviewService)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Alta de reseña con saga mapeada: el libro se suma solo al final")
    void altaConSagaMapeada() {
        String uno = serie("Uno");
        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));

        Review rv = reviewService.createReviewForNewBook(newReview(PREFIX + "Nuevo " + suf + " (" + uno + ", #3)"));
        long nuevo = rv.getBook().getId();
        createdBookIds.add(nuevo);

        SagaDetail s = sagaService.get(sagaId);
        assertEquals(List.of(a, b, nuevo), ids(s));
        assertTrue(s.autoDetected(), "sumar un libro nuevo no la vuelve de Camila");
    }

    @Test
    @DisplayName("Renombrar un libro a un titulo con saga mapeada lo suma; si ya esta, no hace nada")
    void renombreConSagaMapeada() {
        String uno = serie("Uno");
        long a = book("A", uno, ", #1");
        long b = book("B", uno, ", #2");
        autoService.apply(mine());
        long sagaId = sagaOfKey(key("Uno"));

        Review rv = reviewService.createReviewForNewBook(newReview(PREFIX + "Suelto " + suf));
        long suelto = rv.getBook().getId();
        createdBookIds.add(suelto);
        assertEquals(2, sagaService.get(sagaId).bookCount(), "sin saga en el titulo, nada");

        UpdateReviewRequest ren = new UpdateReviewRequest();
        ren.setTitle(PREFIX + "Suelto " + suf + " (" + uno + ", #3)");
        reviewService.updateReview(suelto, ren);
        assertEquals(List.of(a, b, suelto), ids(sagaService.get(sagaId)));

        UpdateReviewRequest otra = new UpdateReviewRequest();
        otra.setTitle(PREFIX + "Suelto bis " + suf + " (" + uno + ", #3)");
        reviewService.updateReview(suelto, otra);
        assertEquals(3, sagaService.get(sagaId).bookCount(), "ya estaba: no se duplica");
    }

    @Test
    @DisplayName("Alta de reseña con saga descartada (o sin mapear): no se suma a nada")
    void altaConSagaDescartada() {
        String uno = serie("Uno");
        book("A", uno, ", #1");
        book("B", uno, ", #2");
        autoService.apply(mine());
        sagaService.delete(sagaOfKey(key("Uno")));

        Review rv = reviewService.createReviewForNewBook(newReview(PREFIX + "Nuevo " + suf + " (" + uno + ", #3)"));
        createdBookIds.add(rv.getBook().getId());
        assertTrue(sagaService.sagasOfBook(rv.getBook().getId()).isEmpty());

        // Sin mapear (nunca se armo): tampoco.
        Review rv2 = reviewService.createReviewForNewBook(
                newReview(PREFIX + "Otro " + suf + " (" + serie("Nunca") + ", #1)"));
        createdBookIds.add(rv2.getBook().getId());
        assertTrue(sagaService.sagasOfBook(rv2.getBook().getId()).isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    // Biblioteca entera
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Preview real (biblioteca entera, solo lectura): consistente y sin escribir nada")
    void previewReal() {
        long t0 = System.nanoTime();
        AutoSagaPreview p = autoService.preview();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int suma = p.create().stream().mapToInt(AutoSagaPreview.Create::bookCount).sum()
                + p.extend().stream().mapToInt(AutoSagaPreview.Extend::addCount).sum();
        assertEquals(suma, p.totalBooks());
        assertTrue(p.create().stream().allMatch(c -> c.bookCount() >= 2));

        System.out.println("[AUTO-SAGAS] preview real en " + ms + " ms: create=" + p.create().size()
                + " extend=" + p.extend().size() + " totalBooks=" + p.totalBooks()
                + " skippedDismissed=" + p.skippedDismissed());
        System.out.println("[AUTO-SAGAS] primeras 10: " + p.create().stream().limit(10)
                .map(c -> c.name() + " (" + c.bookCount() + ")").toList());
    }

    @Test
    @DisplayName("Apply real (biblioteca entera) dentro de una transaccion con rollback: mide el tiempo, no deja nada")
    void applyRealConRollback() {
        AutoSagaPreview p = autoService.preview();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        AutoSagaApplyResult r = tx.execute(status -> {
            long t0 = System.nanoTime();
            AutoSagaApplyResult res = autoService.apply();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[AUTO-SAGAS] apply real en " + ms + " ms: created=" + res.created()
                    + " extended=" + res.extended() + " booksAdded=" + res.booksAdded()
                    + " skipped=" + res.skippedConflicts());
            assertEquals(sagasBefore + res.created(), count("sagas"), "dentro de la transaccion estan todas");
            assertEquals(sagaBooksBefore + res.booksAdded(), count("saga_books"));
            status.setRollbackOnly();
            return res;
        });
        assertNotNull(r);
        assertEquals(p.create().size(), r.created() + r.skippedConflicts().size(), "apply hace lo del preview");
        assertEquals(p.extend().size(), r.extended());
        assertEquals(p.totalBooks(), r.booksAdded());
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Nombre de una serie del test: "ZZ Serie <x> <suf>". */
    private String serie(String x) {
        return "ZZ Serie " + x + " " + suf;
    }

    private String key(String x) {
        return TagSlugNormalizer.toSlug(serie(x));
    }

    /** Solo las claves de este test (sufijo unico). */
    private Predicate<String> mine() {
        return k -> k.startsWith("zz-serie-") && k.endsWith("-" + suf);
    }

    /** Libro "ZZ Test Auto <label> <suf> (<serie><tomo>)"; tomo null = sin saga. */
    private long book(String label, String series, String tomo) {
        String title = PREFIX + label + " " + suf + (tomo == null ? "" : " (" + series + tomo + ")");
        Long id = jdbc.queryForObject("""
                INSERT INTO books (title, author, has_url_cover) VALUES (?, ?, false) RETURNING id
                """, Long.class, title, "ZZ Autora Test");
        createdBookIds.add(id);
        return id;
    }

    private NewReviewRequest newReview(String title) {
        NewReviewRequest r = new NewReviewRequest();
        r.setTitle(title);
        r.setAuthor("ZZ Autora Test");
        r.setRating(BigDecimal.ZERO);
        return r;
    }

    private NewSagaRequest req(String name) {
        NewSagaRequest r = new NewSagaRequest();
        r.setName(name);
        return r;
    }

    private long sagaOfKey(String key) {
        Long id = jdbc.queryForObject("SELECT saga_id FROM saga_series_keys WHERE series_key = ?", Long.class, key);
        assertNotNull(id, "la clave " + key + " no apunta a ninguna saga");
        createdSagaIds.add(id);
        return id;
    }

    private long dismissedCount(long sagaId, long bookId) {
        return jdbc.queryForObject("SELECT count(*) FROM saga_book_dismissed WHERE saga_id = ? AND book_id = ?",
                Long.class, sagaId, bookId);
    }

    private static List<Long> ids(SagaDetail d) {
        return d.books().stream().map(SagaBook::bookId).toList();
    }

    private static List<Integer> positions(SagaDetail d) {
        return d.books().stream().map(SagaBook::position).toList();
    }

    private static void status(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, e.getStatusCode(), e.getReason());
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? -1 : n;
    }

    private BigDecimal ratingSum() {
        BigDecimal s = jdbc.queryForObject("SELECT COALESCE(sum(rating), 0) FROM reviews", BigDecimal.class);
        return s == null ? BigDecimal.ZERO : s;
    }
}
