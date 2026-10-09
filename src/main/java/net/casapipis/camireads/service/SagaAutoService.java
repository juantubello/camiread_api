package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.saga.AutoSagaApplyResult;
import net.casapipis.camireads.dto.saga.AutoSagaPreview;
import net.casapipis.camireads.dto.saga.AutoSagaUndoPreview;
import net.casapipis.camireads.dto.saga.AutoSagaUndoResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Armado automatico de sagas (Fase 10b): crea/extiende sagas a partir del
 * titulo de Goodreads "Libro (Saga, #N)". Esquema y semantica de la memoria
 * del armado: db/migrations/007_sagas_auto.sql.
 *
 * PREVIEW Y APPLY COMPARTEN EL MISMO CALCULO: los dos leen el mismo estado
 * (loadState) y lo pasan por la misma funcion pura (plan). Apply solo agrega
 * los locks y escribe lo que el plan dice; asi lo que Camila ve en el preview
 * es exactamente lo que pasa al confirmar (salvo que algo cambie en el medio).
 *
 * Reglas por cada saga del titulo (clave k = slug del nombre):
 *   1. saga_series_keys[k] con saga_id NULL -> la borro ella: no se toca.
 *   2. saga_series_keys[k] = S -> se extiende S con los libros de k que no
 *      tiene y que ella no saco a mano (saga_book_dismissed).
 *   3. sin clave pero hay una saga con slug = k (la armo a mano con ese
 *      nombre) -> se registra la clave y se extiende esa.
 *   4. nada de lo anterior y k tiene 2+ libros -> saga nueva, auto_detected.
 *
 * RENDIMIENTO (el server es un i3 de 2 nucleos): cantidad FIJA de queries,
 * sin importar cuantas sagas o libros haya: 5 lecturas del estado + 1 insert
 * multi-fila de sagas + 3 batch (claves, saga_books, updated_at). Nada por libro.
 */
@Service
@RequiredArgsConstructor
public class SagaAutoService {

    /**
     * Clave del pg_advisory_xact_lock que serializa apply y undo entre si
     * (dos "armar" en simultaneo crearian las mismas sagas dos veces). Numero
     * arbitrario, solo tiene que ser siempre el mismo. Se suelta solo al
     * terminar la transaccion.
     */
    private static final long AUTO_LOCK = 1_026_100_002L;

    private final JdbcTemplate jdbc;

    // ─────────────────────────────────────────────────────────────
    // Endpoints
    // ─────────────────────────────────────────────────────────────

    /** GET /sagas/auto/preview — solo lectura. */
    @Transactional(readOnly = true)
    public AutoSagaPreview preview() {
        return preview(k -> true);
    }

    /** POST /sagas/auto/apply — todo en UNA transaccion: o se arma todo o nada. */
    @Transactional
    public AutoSagaApplyResult apply() {
        return apply(k -> true);
    }

    /** GET /sagas/auto/undo-preview — cuantas sagas automaticas sin tocar hay. */
    @Transactional(readOnly = true)
    public AutoSagaUndoPreview undoPreview() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM sagas WHERE auto_detected", Long.class);
        return new AutoSagaUndoPreview(n == null ? 0 : n.intValue());
    }

    /** POST /sagas/auto/undo — borra las sagas automaticas que Camila no edito. */
    @Transactional
    public AutoSagaUndoResult undo() {
        return new AutoSagaUndoResult(undo(null));
    }

    // ─────────────────────────────────────────────────────────────
    // Variantes acotadas (package-private, para los tests)
    //
    // El armado real recorre TODA la biblioteca (~300 sagas en el sandbox).
    // Los tests no pueden crear 300 sagas de Camila para probar un caso, asi
    // que corren EL MISMO codigo filtrando por las claves de sus libros
    // ficticios. Con keyFilter = "todo" es exactamente el endpoint.
    // (Spring 6 aplica @Transactional tambien a metodos package-private.)
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    AutoSagaPreview preview(Predicate<String> keyFilter) {
        return toPreview(plan(loadState(), keyFilter));
    }

    @Transactional
    AutoSagaApplyResult apply(Predicate<String> keyFilter) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, AUTO_LOCK);
        // Se bloquean TODAS las sagas (son cientos de filas, es barato) y en
        // orden de id, igual que el merge: asi ninguna edicion de Camila se
        // mete entre la lectura del estado y la escritura (ej. sacar un libro
        // que el plan iba a re-agregar, o borrar una saga a extender).
        jdbc.query("SELECT id FROM sagas ORDER BY id FOR UPDATE", rs -> { });

        Plan plan = plan(loadState(), keyFilter);

        // 1. Claves de sagas que ya existian (regla 3: la que armo a mano).
        if (!plan.links().isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO saga_series_keys (series_key, saga_id) VALUES (?, ?)
                    ON CONFLICT (series_key) DO NOTHING
                    """, plan.links().entrySet().stream()
                    .map(e -> new Object[]{e.getKey(), e.getValue()})
                    .toList());
        }

        // 2. Sagas nuevas: UN insert multi-fila (unnest de arrays). El ON
        //    CONFLICT cubre un slug que ya tenga otra saga: esa se saltea y se
        //    reporta (no deberia pasar: la regla 3 la hubiera extendido).
        Map<String, Long> createdIds = insertSagas(plan.create());
        List<String> skipped = new ArrayList<>();
        List<Object[]> keyRows = new ArrayList<>();
        List<Object[]> bookRows = new ArrayList<>();
        int booksAdded = 0;
        for (NewSaga n : plan.create()) {
            Long id = createdIds.get(n.key());
            if (id == null) {
                skipped.add(n.name());
                continue;
            }
            keyRows.add(new Object[]{n.key(), id});
            for (int i = 0; i < n.books().size(); i++) {
                bookRows.add(new Object[]{id, n.books().get(i).bookId(), i + 1});
            }
            booksAdded += n.books().size();
        }

        // 3. Extensiones: al final de lo que la saga ya tenia.
        for (Extension x : plan.extend()) {
            for (int i = 0; i < x.books().size(); i++) {
                bookRows.add(new Object[]{x.sagaId(), x.books().get(i).bookId(), x.startPosition() + i});
            }
            booksAdded += x.books().size();
        }

        if (!keyRows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO saga_series_keys (series_key, saga_id) VALUES (?, ?)
                    ON CONFLICT (series_key) DO NOTHING
                    """, keyRows);
        }
        if (!bookRows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO saga_books (saga_id, book_id, position) VALUES (?, ?, ?)", bookRows);
        }
        // Las extendidas suben arriba en el listado, pero NO cambian de dueña:
        // auto_detected queda como estaba.
        if (!plan.extend().isEmpty()) {
            jdbc.batchUpdate("UPDATE sagas SET updated_at = now() WHERE id = ?",
                    plan.extend().stream().map(x -> new Object[]{x.sagaId()}).toList());
        }

        return new AutoSagaApplyResult(plan.create().size() - skipped.size(), plan.extend().size(),
                booksAdded, skipped);
    }

    /**
     * Deshacer. Borra PRIMERO las claves de esas sagas: deshacer no es
     * "descartar". Si se borrara solo la saga, el ON DELETE SET NULL dejaria
     * la clave descartada y el proximo armado ya no la crearia.
     *
     * El FOR UPDATE con "WHERE auto_detected" hace que una saga que Camila
     * esta editando justo ahora se re-evalue al soltarse el lock: si la edito
     * (paso a false), ya no entra.
     *
     * @param onlyIds null = todas; si no, solo esas (para los tests).
     */
    @Transactional
    int undo(Collection<Long> onlyIds) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, AUTO_LOCK);
        List<Long> ids = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sagas WHERE auto_detected ORDER BY id FOR UPDATE", Long.class));
        if (onlyIds != null) {
            ids.retainAll(new HashSet<>(onlyIds));
        }
        if (ids.isEmpty()) {
            return 0;
        }
        updateWithIds("DELETE FROM saga_series_keys WHERE saga_id = ANY (?)", ids);
        return updateWithIds("DELETE FROM sagas WHERE id = ANY (?) AND auto_detected", ids);
    }

    /**
     * Libro nuevo o renombrado: si su titulo trae una saga que el armado ya
     * conoce (saga_series_keys[k] = S, no descartada), se agrega al final de S.
     * Si la clave no existe o esta descartada, o el libro ya esta en S, o
     * Camila lo saco de S a mano, no hace nada.
     *
     * REQUIRES_NEW: ReviewService lo llama DESPUES del commit del alta (ver
     * ahi el porque), y desde afterCommit cualquier acceso a datos tiene que
     * abrir su propia transaccion.
     *
     * @return la saga a la que se agrego, o null si no se agrego a ninguna.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long attachToMappedSaga(long bookId) {
        List<String> titles = jdbc.queryForList("SELECT title FROM books WHERE id = ?", String.class, bookId);
        if (titles.isEmpty()) {
            return null;
        }
        SeriesDetector.Series series = SeriesDetector.detect(titles.get(0));
        if (series == null) {
            return null;
        }
        List<Long> mapped = jdbc.queryForList(
                "SELECT saga_id FROM saga_series_keys WHERE series_key = ? AND saga_id IS NOT NULL",
                Long.class, series.key());
        if (mapped.isEmpty()) {
            return null;
        }
        long sagaId = mapped.get(0);

        // Mismo lock que las ediciones a mano: serializa el max(position)+1.
        if (jdbc.queryForList("SELECT id FROM sagas WHERE id = ? FOR UPDATE", Long.class, sagaId).isEmpty()) {
            return null;
        }
        Long blocked = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM saga_books WHERE saga_id = ? AND book_id = ?)
                     + (SELECT count(*) FROM saga_book_dismissed WHERE saga_id = ? AND book_id = ?)
                """, Long.class, sagaId, bookId, sagaId, bookId);
        if (blocked != null && blocked > 0) {
            return null;
        }
        jdbc.update("""
                INSERT INTO saga_books (saga_id, book_id, position)
                SELECT ?, ?, COALESCE(max(position), 0) + 1 FROM saga_books WHERE saga_id = ?
                """, sagaId, bookId, sagaId);
        jdbc.update("UPDATE sagas SET updated_at = now() WHERE id = ?", sagaId);
        return sagaId;
    }

    // ─────────────────────────────────────────────────────────────
    // Estado y plan (el calculo compartido)
    // ─────────────────────────────────────────────────────────────

    record BookRow(long id, String title) {
    }

    record SagaRef(long id, String name) {
    }

    /**
     * Foto de todo lo que el plan necesita. Las sagas de saga_books vienen
     * como "id de saga -> libros" y "id de saga -> ultima posicion".
     */
    record State(List<BookRow> books,
                 Map<String, Long> keys,              // valor null = clave descartada
                 Map<String, SagaRef> sagasBySlug,
                 Map<Long, String> sagaNames,
                 Map<Long, Set<Long>> sagaBooks,
                 Map<Long, Integer> maxPosition,
                 Map<Long, Set<Long>> dismissed) {
    }

    record Item(long bookId, String title, String seriesName, BigDecimal volume) {
    }

    record NewSaga(String key, String name, List<Item> books) {
    }

    record Extension(long sagaId, String name, int startPosition, List<Item> books) {
    }

    /**
     * links: claves a registrar contra sagas que ya existian (regla 3).
     * Pueden estar aunque la saga no sume libros (asi un renombre posterior
     * no la "pierde").
     */
    record Plan(List<NewSaga> create, List<Extension> extend, Map<String, Long> links, int skippedDismissed) {
    }

    /** Tomo asc, despues titulo (orden natural), despues id. */
    static final Comparator<Item> VOLUME_ORDER = Comparator
            .comparing(Item::volume)
            .thenComparing(Item::title, SeriesDetector.NATURAL)
            .thenComparingLong(Item::bookId);

    /** 5 queries fijas. Solo libros con "#" en el titulo: los demas no pueden tener saga. */
    private State loadState() {
        List<BookRow> books = jdbc.query("SELECT id, title FROM books WHERE strpos(title, '#') > 0",
                (rs, i) -> new BookRow(rs.getLong("id"), rs.getString("title")));

        Map<String, Long> keys = new HashMap<>();
        jdbc.query("SELECT series_key, saga_id FROM saga_series_keys", rs -> {
            // getObject y no getLong+wasNull: el NULL (= descartada) tiene que
            // llegar como null, nunca como 0.
            keys.put(rs.getString("series_key"), rs.getObject("saga_id", Long.class));
        });

        Map<String, SagaRef> bySlug = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        jdbc.query("SELECT id, name, slug FROM sagas", rs -> {
            SagaRef ref = new SagaRef(rs.getLong("id"), rs.getString("name"));
            bySlug.put(rs.getString("slug"), ref);
            names.put(ref.id(), ref.name());
        });

        Map<Long, Set<Long>> sagaBooks = new HashMap<>();
        Map<Long, Integer> maxPos = new HashMap<>();
        jdbc.query("SELECT saga_id, book_id, position FROM saga_books", rs -> {
            long saga = rs.getLong("saga_id");
            sagaBooks.computeIfAbsent(saga, k -> new HashSet<>()).add(rs.getLong("book_id"));
            maxPos.merge(saga, rs.getInt("position"), Math::max);
        });

        Map<Long, Set<Long>> dismissed = new HashMap<>();
        jdbc.query("SELECT saga_id, book_id FROM saga_book_dismissed", rs -> {
            dismissed.computeIfAbsent(rs.getLong("saga_id"), k -> new HashSet<>()).add(rs.getLong("book_id"));
        });

        return new State(books, keys, bySlug, names, sagaBooks, maxPos, dismissed);
    }

    /** Funcion pura: dado el estado, que hay que crear y que extender. */
    static Plan plan(State st, Predicate<String> keyFilter) {
        // TreeMap: recorrido por clave, determinista (el preview no "baila").
        Map<String, List<Item>> byKey = new TreeMap<>();
        for (BookRow b : st.books()) {
            SeriesDetector.Series s = SeriesDetector.detect(b.title());
            if (s == null || !keyFilter.test(s.key())) {
                continue;
            }
            byKey.computeIfAbsent(s.key(), k -> new ArrayList<>())
                    .add(new Item(b.id(), b.title(), s.name(), s.volume()));
        }

        List<NewSaga> create = new ArrayList<>();
        Map<String, Long> links = new LinkedHashMap<>();
        // Una saga puede responder a varias claves (despues de un merge): se
        // junta todo lo que suma antes de calcular posiciones.
        Map<Long, List<Item>> pending = new LinkedHashMap<>();
        int skippedDismissed = 0;

        for (Map.Entry<String, List<Item>> e : byKey.entrySet()) {
            String key = e.getKey();
            List<Item> items = e.getValue();
            items.sort(VOLUME_ORDER);

            Long target;
            if (st.keys().containsKey(key)) {
                target = st.keys().get(key);
                if (target == null) {          // regla 1: descartada
                    skippedDismissed++;
                    continue;
                }
            } else if (st.sagasBySlug().containsKey(key)) {   // regla 3
                target = st.sagasBySlug().get(key).id();
                links.put(key, target);
            } else {                                            // regla 4
                if (items.size() >= 2) {
                    create.add(new NewSaga(key, nameOf(items), List.copyOf(items)));
                }
                continue;
            }

            // regla 2 (y 3): lo que falta y no fue descartado a mano
            Set<Long> in = st.sagaBooks().getOrDefault(target, Set.of());
            Set<Long> out = st.dismissed().getOrDefault(target, Set.of());
            for (Item it : items) {
                if (!in.contains(it.bookId()) && !out.contains(it.bookId())) {
                    pending.computeIfAbsent(target, k -> new ArrayList<>()).add(it);
                }
            }
        }

        List<Extension> extend = new ArrayList<>();
        for (Map.Entry<Long, List<Item>> e : pending.entrySet()) {
            List<Item> items = new ArrayList<>(e.getValue());
            items.sort(VOLUME_ORDER);
            long sagaId = e.getKey();
            extend.add(new Extension(sagaId, st.sagaNames().get(sagaId),
                    st.maxPosition().getOrDefault(sagaId, 0) + 1, List.copyOf(items)));
        }

        create.sort(Comparator.comparing(NewSaga::name, SeriesDetector.NATURAL));
        extend.sort(Comparator.comparing(Extension::name, SeriesDetector.NATURAL));
        return new Plan(create, extend, links, skippedDismissed);
    }

    /** Nombre visible: como lo escribe el libro de menor tomo (empate: menor id). */
    private static String nameOf(List<Item> items) {
        return items.stream()
                .min(Comparator.comparing(Item::volume).thenComparingLong(Item::bookId))
                .orElseThrow()
                .seriesName();
    }

    private static AutoSagaPreview toPreview(Plan plan) {
        List<AutoSagaPreview.Create> create = plan.create().stream()
                .map(n -> new AutoSagaPreview.Create(n.name(), n.books().size(), books(n.books())))
                .toList();
        List<AutoSagaPreview.Extend> extend = plan.extend().stream()
                .map(x -> new AutoSagaPreview.Extend(x.sagaId(), x.name(), x.books().size(), books(x.books())))
                .toList();
        int total = create.stream().mapToInt(AutoSagaPreview.Create::bookCount).sum()
                + extend.stream().mapToInt(AutoSagaPreview.Extend::addCount).sum();
        return new AutoSagaPreview(create, extend, total, plan.skippedDismissed());
    }

    private static List<AutoSagaPreview.Book> books(List<Item> items) {
        return items.stream().map(i -> new AutoSagaPreview.Book(i.bookId(), i.title())).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // SQL con arrays
    // ─────────────────────────────────────────────────────────────

    /**
     * Inserta todas las sagas nuevas en una sola sentencia y devuelve
     * slug -> id de las que entraron (las que chocaron por slug no vuelven).
     * WITH ORDINALITY + ORDER BY: los ids salen en el orden del preview.
     */
    private Map<String, Long> insertSagas(List<NewSaga> create) {
        Map<String, Long> ids = new HashMap<>();
        if (create.isEmpty()) {
            return ids;
        }
        String[] names = create.stream().map(NewSaga::name).toArray(String[]::new);
        String[] slugs = create.stream().map(NewSaga::key).toArray(String[]::new);
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO sagas (name, slug, auto_detected)
                    SELECT t.name, t.slug, true
                    FROM unnest(?::text[], ?::text[]) WITH ORDINALITY AS t(name, slug, ord)
                    ORDER BY t.ord
                    ON CONFLICT (slug) DO NOTHING
                    RETURNING id, slug
                    """);
            ps.setArray(1, con.createArrayOf("text", names));
            ps.setArray(2, con.createArrayOf("text", slugs));
            return ps;
        }, rs -> {
            ids.put(rs.getString("slug"), rs.getLong("id"));
        });
        return ids;
    }

    private int updateWithIds(String sql, Collection<Long> ids) {
        Long[] arr = ids.toArray(Long[]::new);
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("bigint", arr));
            return ps;
        });
    }
}
