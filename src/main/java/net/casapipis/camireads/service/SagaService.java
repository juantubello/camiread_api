package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.saga.BookSaga;
import net.casapipis.camireads.dto.saga.NewSagaRequest;
import net.casapipis.camireads.dto.saga.SagaBook;
import net.casapipis.camireads.dto.saga.SagaDetail;
import net.casapipis.camireads.dto.saga.SagaSummary;
import net.casapipis.camireads.dto.saga.UpdateSagaRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mis sagas (Fase 10): agrupador de libros con nombre, imagen y ORDEN MANUAL.
 * Esquema y reglas: db/migrations/006_sagas.sql.
 *
 * POR QUE JdbcTemplate Y NO ENTIDADES JPA: casi todo lo interesante aca son
 * lecturas armadas (collage con las 4 primeras tapas, LEFT JOIN a reviews,
 * conteos) y escrituras de posiciones en bloque. En SQL se leen de una y no
 * hay que pelearse con el orden de flush de Hibernate al mezclar entidades
 * con updates masivos. Todo corre en la transaccion de Spring (el
 * JpaTransactionManager le presta la misma conexion al JdbcTemplate).
 *
 * Igual que ProfileService: todo lo que la base impone con un CHECK se valida
 * ACA primero, para contestar 400/409 en castellano y no un 500 con el SQL.
 *
 * Ninguna sentencia de esta clase escribe en books ni en reviews: solo
 * sagas y saga_books.
 */
@Service
@RequiredArgsConstructor
public class SagaService {

    /** sagas.name y sagas.slug son VARCHAR(120). */
    private static final int MAX_NAME = 120;

    /**
     * Techo del CHECK sagas_cover_size, replicado aca (mismo numero y mismo
     * motivo que app_profile: ~300 KB de imagen, el front la achica antes).
     */
    static final int MAX_COVER_B64_CHARS = 400_000;

    /** Lo que pide el contrato con el front; image/jpg se normaliza a jpeg. */
    private static final Set<String> ALLOWED_MIMES = Set.of("image/jpeg", "image/png", "image/webp");

    /** Tapas que manda el listado para el collage. */
    private static final int PREVIEW_COVERS = 4;

    private final JdbcTemplate jdbc;

    // ─────────────────────────────────────────────────────────────
    // Lecturas
    // ─────────────────────────────────────────────────────────────

    /**
     * GET /sagas — DOS queries fijas, sin importar cuantas sagas haya:
     *   1. las sagas con su conteo de libros
     *   2. las (hasta) 4 primeras tapas de cada saga
     */
    @Transactional(readOnly = true)
    public List<SagaSummary> list() {
        List<SagaRow> sagas = jdbc.query("""
                SELECT s.id, s.name, s.url_cover, s.cover_b64, s.cover_mime, s.updated_at,
                       (SELECT count(*) FROM saga_books sb WHERE sb.saga_id = s.id) AS book_count
                FROM sagas s
                ORDER BY s.updated_at DESC, s.id DESC
                """, SAGA_ROW);

        if (sagas.isEmpty()) {
            return List.of();
        }

        // OJO RENDIMIENTO: el row_number se calcula sobre ids y posiciones, y
        // recien despues se va a books a buscar la tapa de los que quedaron
        // (rn <= 4). Asi Postgres nunca destostea (TOAST) las b64 de los
        // libros que no entran en el collage. "b64_cover IS NOT NULL" no lee
        // el contenido, solo el bit de nulo.
        Map<Long, List<String>> previews = new LinkedHashMap<>();
        jdbc.query("""
                WITH ranked AS (
                    SELECT sb.saga_id, sb.book_id, sb.position,
                           row_number() OVER (PARTITION BY sb.saga_id
                                              ORDER BY sb.position, sb.book_id) AS rn
                    FROM saga_books sb
                    JOIN books b ON b.id = sb.book_id
                    WHERE NULLIF(btrim(b.url_cover), '') IS NOT NULL
                       OR b.b64_cover IS NOT NULL
                )
                SELECT r.saga_id, b.url_cover, b.b64_cover
                FROM ranked r
                JOIN books b ON b.id = r.book_id
                WHERE r.rn <= ?
                ORDER BY r.saga_id, r.position, r.book_id
                """, rs -> {
            String cover = bookCover(rs.getString("url_cover"), rs.getString("b64_cover"));
            if (cover != null) {
                previews.computeIfAbsent(rs.getLong("saga_id"), k -> new ArrayList<>()).add(cover);
            }
        }, PREVIEW_COVERS);

        return sagas.stream()
                .map(s -> s.toSummary(previews.getOrDefault(s.id(), List.of())))
                .toList();
    }

    /** GET /sagas/{id} — la saga + sus libros en orden. 404 si no existe. */
    @Transactional(readOnly = true)
    public SagaDetail get(long id) {
        return detail(id);
    }

    /**
     * GET /books/{bookId}/sagas — en que sagas esta el libro y en que lugar.
     * 404 si el libro no existe (para distinguirlo de "existe pero no esta en
     * ninguna saga", que es []).
     */
    @Transactional(readOnly = true)
    public List<BookSaga> sagasOfBook(long bookId) {
        requireBook(bookId);
        return jdbc.query("""
                SELECT s.id, s.name, sb.position,
                       (SELECT count(*) FROM saga_books x WHERE x.saga_id = s.id) AS book_count
                FROM saga_books sb
                JOIN sagas s ON s.id = sb.saga_id
                WHERE sb.book_id = ?
                ORDER BY lower(s.name), s.id
                """, (rs, i) -> new BookSaga(
                rs.getLong("id"), rs.getString("name"), rs.getInt("position"), rs.getLong("book_count")),
                bookId);
    }

    // ─────────────────────────────────────────────────────────────
    // Alta / modificacion / baja de la saga
    // ─────────────────────────────────────────────────────────────

    /** POST /sagas — 400 por datos invalidos, 409 si ya hay una saga con ese slug. */
    @Transactional
    public SagaDetail create(NewSagaRequest request) {
        if (request == null) {
            throw bad("Falta el cuerpo del pedido");
        }
        String name = cleanName(request.getName());
        String slug = slugOrFail(name);

        String url = blankToNull(request.getUrlCover());
        String dataUrl = blankToNull(request.getCoverDataUrl());
        if (url != null && dataUrl != null) {
            throw bothCovers();
        }
        Photo photo = dataUrl == null ? null : parseCover(dataUrl);

        failIfSlugTaken(slug, null);

        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO sagas (name, slug, url_cover, cover_b64, cover_mime)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class,
                    name, slug, url,
                    photo == null ? null : photo.b64(),
                    photo == null ? null : photo.mime());
        } catch (DuplicateKeyException e) {
            // Carrera: otro pedido la creo entre el chequeo y el insert.
            throw nameTaken(name);
        }
        return detail(id);
    }

    /**
     * PUT /sagas/{id} — parcial. Mandar urlCover borra la foto y viceversa
     * (el CHECK sagas_one_cover no deja tener las dos); clearCover borra ambas.
     */
    @Transactional
    public SagaDetail update(long id, UpdateSagaRequest request) {
        lockSaga(id);
        if (request == null) {
            throw bad("Falta el cuerpo del pedido");
        }

        String url = blankToNull(request.getUrlCover());
        String dataUrl = blankToNull(request.getCoverDataUrl());
        boolean clear = Boolean.TRUE.equals(request.getClearCover());

        if (url != null && dataUrl != null) {
            throw bothCovers();
        }
        if (clear && (url != null || dataUrl != null)) {
            throw bad("O borrás la imagen (clearCover) o mandás una nueva, las dos cosas juntas no");
        }

        if (request.getName() != null) {
            String name = cleanName(request.getName());
            String slug = slugOrFail(name);
            failIfSlugTaken(slug, id);
            try {
                jdbc.update("UPDATE sagas SET name = ?, slug = ? WHERE id = ?", name, slug, id);
            } catch (DuplicateKeyException e) {
                throw nameTaken(name);
            }
        }

        if (clear) {
            jdbc.update("UPDATE sagas SET url_cover = NULL, cover_b64 = NULL, cover_mime = NULL WHERE id = ?", id);
        } else if (url != null) {
            jdbc.update("UPDATE sagas SET url_cover = ?, cover_b64 = NULL, cover_mime = NULL WHERE id = ?", url, id);
        } else if (dataUrl != null) {
            Photo photo = parseCover(dataUrl);
            jdbc.update("UPDATE sagas SET url_cover = NULL, cover_b64 = ?, cover_mime = ? WHERE id = ?",
                    photo.b64(), photo.mime(), id);
        }

        touch(id);
        return detail(id);
    }

    /**
     * DELETE /sagas/{id} — el CASCADE se lleva sus filas de saga_books.
     * Los libros NO se tocan: el CASCADE va de sagas hacia saga_books, nunca
     * hacia books.
     */
    @Transactional
    public void delete(long id) {
        if (jdbc.update("DELETE FROM sagas WHERE id = ?", id) == 0) {
            throw sagaNotFound(id);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Libros de la saga
    // ─────────────────────────────────────────────────────────────

    /**
     * POST /sagas/{id}/books — agrega al FINAL (max(position)+1).
     *
     * El SELECT ... FOR UPDATE sobre la saga serializa los cambios de una
     * misma saga: sin eso, dos "agregar" simultaneos calcularian el mismo
     * max+1 y uno reventaria en el COMMIT contra ux_saga_books_position.
     */
    @Transactional
    public SagaDetail addBook(long sagaId, Long bookId) {
        String sagaName = lockSaga(sagaId);
        if (bookId == null) {
            throw bad("Falta el bookId del libro a agregar");
        }
        requireBook(bookId);

        Long already = jdbc.queryForObject(
                "SELECT count(*) FROM saga_books WHERE saga_id = ? AND book_id = ?", Long.class, sagaId, bookId);
        if (already != null && already > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese libro ya está en la saga «" + sagaName + "»");
        }

        jdbc.update("""
                INSERT INTO saga_books (saga_id, book_id, position)
                SELECT ?, ?, COALESCE(max(position), 0) + 1 FROM saga_books WHERE saga_id = ?
                """, sagaId, bookId, sagaId);

        touch(sagaId);
        return detail(sagaId);
    }

    /** DELETE /sagas/{id}/books/{bookId} — saca el libro y renumera 1..n sin huecos. */
    @Transactional
    public SagaDetail removeBook(long sagaId, long bookId) {
        String sagaName = lockSaga(sagaId);
        int removed = jdbc.update("DELETE FROM saga_books WHERE saga_id = ? AND book_id = ?", sagaId, bookId);
        if (removed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese libro no está en la saga «" + sagaName + "»");
        }
        renumber(sagaId);
        touch(sagaId);
        return detail(sagaId);
    }

    /**
     * PUT /sagas/{id}/order — reescribe las posiciones 1..n.
     *
     * bookIds tiene que ser EXACTAMENTE una permutacion de los libros que hoy
     * estan en la saga: ni faltantes, ni sobrantes, ni repetidos. Si no, 400 y
     * no se toca nada (la validacion va antes de cualquier UPDATE).
     *
     * Los choques transitorios de posicion (el 1 pasa a 2 mientras el 2
     * todavia es 2) no revientan porque ux_saga_books_position es DEFERRABLE
     * INITIALLY DEFERRED: Postgres lo chequea recien en el COMMIT.
     */
    @Transactional
    public SagaDetail reorder(long sagaId, List<Long> bookIds) {
        lockSaga(sagaId);
        if (bookIds == null) {
            throw bad("Falta bookIds con el orden nuevo");
        }

        List<Long> current = jdbc.queryForList(
                "SELECT book_id FROM saga_books WHERE saga_id = ?", Long.class, sagaId);
        Set<Long> currentSet = new HashSet<>(current);

        Set<Long> seen = new HashSet<>();
        for (Long bookId : bookIds) {
            if (bookId == null) {
                throw bad("bookIds no puede tener valores vacíos");
            }
            if (!seen.add(bookId)) {
                throw bad("El libro " + bookId + " aparece más de una vez en el orden");
            }
            if (!currentSet.contains(bookId)) {
                throw bad("El libro " + bookId + " no está en esta saga");
            }
        }
        if (seen.size() != currentSet.size()) {
            throw bad("El orden tiene que incluir los " + currentSet.size()
                    + " libros de la saga (llegaron " + seen.size() + ")");
        }

        List<Object[]> args = new ArrayList<>(bookIds.size());
        for (int i = 0; i < bookIds.size(); i++) {
            args.add(new Object[]{i + 1, sagaId, bookIds.get(i)});
        }
        jdbc.batchUpdate("UPDATE saga_books SET position = ? WHERE saga_id = ? AND book_id = ?", args);

        touch(sagaId);
        return detail(sagaId);
    }

    // ─────────────────────────────────────────────────────────────
    // Soporte
    // ─────────────────────────────────────────────────────────────

    private SagaDetail detail(long id) {
        List<SagaRow> rows = jdbc.query("""
                SELECT s.id, s.name, s.url_cover, s.cover_b64, s.cover_mime, s.updated_at,
                       (SELECT count(*) FROM saga_books sb WHERE sb.saga_id = s.id) AS book_count
                FROM sagas s
                WHERE s.id = ?
                """, SAGA_ROW, id);
        if (rows.isEmpty()) {
            throw sagaNotFound(id);
        }
        SagaRow saga = rows.get(0);

        // LEFT JOIN LATERAL a reviews: desde la Fase 8 puede haber libros SIN
        // resenia (rating 0). Y si un libro tiene dos resenias (pasa con dos
        // libros de la base), se toma la mas reciente: una fila por libro.
        List<SagaBook> books = jdbc.query("""
                SELECT sb.position, b.id AS book_id, b.title, b.author,
                       b.url_cover, b.b64_cover, b.end_read_date, r.rating
                FROM saga_books sb
                JOIN books b ON b.id = sb.book_id
                LEFT JOIN LATERAL (
                    SELECT rv.rating FROM reviews rv
                    WHERE rv.book_id = b.id
                    ORDER BY rv.id DESC
                    LIMIT 1
                ) r ON true
                WHERE sb.saga_id = ?
                ORDER BY sb.position, sb.book_id
                """, (rs, i) -> {
            BigDecimal rating = rs.getBigDecimal("rating");
            Date end = rs.getDate("end_read_date");
            return new SagaBook(
                    rs.getInt("position"),
                    rs.getLong("book_id"),
                    rs.getString("title"),
                    rs.getString("author"),
                    bookCover(rs.getString("url_cover"), rs.getString("b64_cover")),
                    rating == null ? BigDecimal.ZERO : rating,
                    end == null ? null : end.toLocalDate());
        }, id);

        // Mismo collage que el listado, sacado de los libros que ya tenemos.
        List<String> previews = books.stream()
                .map(SagaBook::urlCover)
                .filter(c -> c != null)
                .limit(PREVIEW_COVERS)
                .toList();

        SagaSummary s = saga.toSummary(previews);
        return new SagaDetail(s.id(), s.name(), s.urlCover(), s.coverDataUrl(), s.bookCount(),
                s.previewCovers(), s.updatedAt(), books);
    }

    /** Bloquea la fila de la saga (o 404) y devuelve su nombre para los mensajes. */
    private String lockSaga(long id) {
        List<String> names = jdbc.queryForList("SELECT name FROM sagas WHERE id = ? FOR UPDATE", String.class, id);
        if (names.isEmpty()) {
            throw sagaNotFound(id);
        }
        return names.get(0);
    }

    private void requireBook(long bookId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM books WHERE id = ?", Long.class, bookId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe el libro " + bookId);
        }
    }

    /** Deja las posiciones 1..n respetando el orden actual (tapa los huecos). */
    private void renumber(long sagaId) {
        jdbc.update("""
                UPDATE saga_books sb
                SET position = r.rn
                FROM (SELECT book_id,
                             row_number() OVER (ORDER BY position, book_id) AS rn
                      FROM saga_books
                      WHERE saga_id = ?) r
                WHERE sb.saga_id = ? AND sb.book_id = r.book_id AND sb.position <> r.rn
                """, sagaId, sagaId);
    }

    /** El listado ordena por updated_at: cualquier cambio sube la saga arriba. */
    private void touch(long sagaId) {
        jdbc.update("UPDATE sagas SET updated_at = now() WHERE id = ?", sagaId);
    }

    private void failIfSlugTaken(String slug, Long exceptId) {
        List<Map<String, Object>> clash = jdbc.queryForList(
                "SELECT id, name FROM sagas WHERE slug = ?", slug);
        if (!clash.isEmpty()) {
            Long clashId = ((Number) clash.get(0).get("id")).longValue();
            if (exceptId == null || !clashId.equals(exceptId)) {
                throw nameTaken((String) clash.get(0).get("name"));
            }
        }
    }

    /**
     * Tapa de un libro para el front: el link si tiene; si no, la b64 como
     * data URL (books no guarda el mime: historicamente son jpeg); si no, null.
     */
    static String bookCover(String url, String b64) {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        if (b64 != null && !b64.isBlank()) {
            return "data:image/jpeg;base64," + b64;
        }
        return null;
    }

    // ── Validaciones ──

    private String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("El nombre de la saga no puede estar vacío");
        }
        String name = raw.trim().replaceAll("\\s{2,}", " ");
        if (name.length() > MAX_NAME) {
            throw bad("El nombre de la saga no puede superar los " + MAX_NAME
                    + " caracteres (llegaron " + name.length() + ")");
        }
        return name;
    }

    /**
     * Mismo normalizador que los tags: "Fénix" y "fenix" son la misma saga.
     * Un nombre hecho solo de simbolos ("¿¿??") no produce slug: 400.
     */
    private String slugOrFail(String name) {
        String slug = TagSlugNormalizer.toSlug(name);
        if (slug.isEmpty()) {
            throw bad("El nombre «" + name + "» necesita al menos una letra o un número");
        }
        if (slug.length() > MAX_NAME) {
            throw bad("El nombre de la saga es demasiado largo");
        }
        return slug;
    }

    private record Photo(String b64, String mime) {
    }

    /**
     * La foto llega como data URL ("data:image/jpeg;base64,..."). Mismo orden
     * de chequeos que ProfileService: formato y mime, despues el LARGO (midiendo
     * un String, barato), y recien al final el decode.
     */
    private Photo parseCover(String raw) {
        String value = raw.trim();
        if (!value.regionMatches(true, 0, "data:", 0, 5)) {
            throw bad("La imagen tiene que venir como data URL: data:image/jpeg;base64,<contenido>");
        }
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw bad("La imagen tiene que venir como data URL completa: data:image/jpeg;base64,<contenido>");
        }
        String header = value.substring(5, comma);
        int semi = header.indexOf(';');
        if (semi < 0 || !header.substring(semi).toLowerCase(Locale.ROOT).contains("base64")) {
            throw bad("La imagen tiene que estar en base64 (data:image/jpeg;base64,<contenido>)");
        }
        String mime = header.substring(0, semi).trim().toLowerCase(Locale.ROOT);
        if (mime.equals("image/jpg")) {
            mime = "image/jpeg";
        }
        if (!ALLOWED_MIMES.contains(mime)) {
            throw bad("El tipo de imagen '" + mime + "' no está permitido. "
                    + "Tiene que ser image/jpeg, image/png o image/webp");
        }

        String payload = value.substring(comma + 1).replaceAll("\\s", "");
        if (payload.isEmpty()) {
            throw bad("La imagen llegó vacía");
        }
        if (payload.length() > MAX_COVER_B64_CHARS) {
            throw bad("La imagen es demasiado grande: " + payload.length()
                    + " caracteres en base64 y el máximo es " + MAX_COVER_B64_CHARS
                    + " (unos 300 KB). Achicala antes de subirla.");
        }
        try {
            Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw bad("La imagen no es base64 válido");
        }
        return new Photo(payload, mime);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException bothCovers() {
        return bad("La saga lleva link O foto, no las dos: elegí una");
    }

    private static ResponseStatusException nameTaken(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Ya tenés una saga que se llama «" + name + "»");
    }

    private static ResponseStatusException sagaNotFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe la saga " + id);
    }

    // ── Fila de sagas ──

    private record SagaRow(Long id, String name, String urlCover, String coverB64, String coverMime,
                           OffsetDateTime updatedAt, long bookCount) {

        SagaSummary toSummary(List<String> previews) {
            String dataUrl = coverB64 == null ? null : "data:" + coverMime + ";base64," + coverB64;
            return new SagaSummary(id, name, urlCover, dataUrl, bookCount, previews, updatedAt);
        }
    }

    private static final RowMapper<SagaRow> SAGA_ROW = (rs, i) -> new SagaRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("url_cover"),
            rs.getString("cover_b64"),
            rs.getString("cover_mime"),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getLong("book_count"));
}
