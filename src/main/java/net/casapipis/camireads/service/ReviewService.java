package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.casapipis.camireads.domain.model.Book;
import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.domain.model.ReviewQuote;
import net.casapipis.camireads.domain.repository.BookRepository;
import net.casapipis.camireads.domain.repository.ReviewRepository;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.casapipis.camireads.dto.NewReviewRequest;
import net.casapipis.camireads.domain.model.ReviewQuote;
import java.time.ZoneId;
import java.util.ArrayList;


@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** Tamanio de lote para los IN (...) de las queries de prefetch. */
    private static final int BATCH = 500;

    /** books.title y books.author son VARCHAR(255) NOT NULL en la base. */
    private static final int MAX_BOOK_FIELD_LEN = 255;

    private final ReviewRepository reviewRepository;
    private final BookRepository bookRepository;
    private final TagService tagService;
    private final SagaAutoService sagaAutoService;

    /**
     * POST /reviews — crea el LIBRO y su primera resenia.
     *
     * Todo lo que la base rechaza (NOT NULL, CHECK, indice unico) se valida
     * ACA y sale como 400/409 con mensaje en castellano. Antes cualquiera de
     * esos casos se escapaba como 500 con el SQL crudo de Postgres en pantalla
     * (ej: 'null value in column "review_text" ... violates not-null
     * constraint'), que es ilegible para la usuaria y ademas filtra el esquema.
     */
    @Transactional
    public Review createReviewForNewBook(NewReviewRequest request) {

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el cuerpo del pedido");
        }

        // Zona horaria Argentina
        ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");
        OffsetDateTime nowAr = OffsetDateTime.now(zone);

        // 1) Crear Book
        //    title/author son NOT NULL VARCHAR(255): obligatorios y trimeados,
        //    igual que en el renombre del PUT.
        String title = cleanBookField(request.getTitle(), "título");
        String author = cleanBookField(request.getAuthor(), "autor");

        // ux_books_title_author: si el par ya existe, 409 legible (mismo
        // mensaje que da el renombre) en vez de que reviente el indice unico.
        Optional<Book> clash = bookRepository.findFirstByTitleAndAuthor(title, author);
        if (clash.isPresent()) {
            throw titleAuthorConflict(title, author, clash.get().getId());
        }

        Book book = new Book();
        book.setTitle(title);
        book.setAuthor(author);

        book.setStartReadDate(request.getStartReadDate());
        book.setEndReadDate(request.getEndReadDate());

        // fecha de creación del libro
        book.setCreatedAt(nowAr);

        // portada
        String cover = (request.getUrlCover() == null || request.getUrlCover().isBlank())
                ? null
                : request.getUrlCover().trim();
        book.setUrlCover(cover);
        book.setHasUrlCover(cover != null);

        try {
            // saveAndFlush (no save): queremos que el INSERT vaya a la base ACA,
            // dentro del try, y no en el commit —donde la violacion del indice
            // unico ya caeria fuera de nuestro alcance y saldria 500.
            bookRepository.saveAndFlush(book);
        } catch (DataIntegrityViolationException e) {
            // Carrera: entre el chequeo de arriba y el insert, otro request creo
            // un libro con ese mismo (title, author).
            throw titleAuthorConflict(title, author, null);
        }

        // Fase 10b: si el titulo trae una saga que el armado ya conoce, el
        // libro se suma solo al final de esa saga (despues del commit).
        attachToSagaAfterCommit(book.getId());

        // 2) Crear Review
        Review review = new Review();
        review.setBook(book);
        review.setRating(requireRating(request.getRating()));
        review.setReviewText(reviewTextOrEmpty(request.getReviewText()));
        review.setCreatedAt(nowAr);

        // 3) Quotes
        var listQuotes = new ArrayList<ReviewQuote>();

        if (request.getQuotes() != null) {
            for (String q : request.getQuotes()) {
                if (q == null || q.isBlank()) continue;

                ReviewQuote rq = new ReviewQuote();
                rq.setReview(review);
                rq.setQuoteText(q);
                rq.setCreatedAt(nowAr);

                listQuotes.add(rq);
            }
        }

        review.setQuotes(listQuotes);

        return reviewRepository.save(review);
    }

    @Transactional
    public void deleteReviewAndBook(Long bookId) {

        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> bookNotFound(bookId));

        // Borramos TODAS las reviews asociadas a ese libro
        // (por si en el futuro permitís más de una)
        var reviews = reviewRepository.findByBook_IdOrderByCreatedAtDesc(bookId);
        if (!reviews.isEmpty()) {
            reviewRepository.deleteAll(reviews);
        }

        // Y por último el libro
        bookRepository.delete(book);
    }

    /**
     * PUT /reviews/book/{bookId} — actualiza el libro y su ultima resenia.
     *
     * Campos ADITIVOS (2026-09): request.title y request.author permiten
     * renombrar el libro. Son OPCIONALES: si llegan null o ausentes, el titulo
     * y el autor no se tocan, asi el front que ya corre en produccion (que
     * manda este PUT sin esos campos) sigue funcionando exactamente igual.
     *
     * Los tags del libro NO se ven afectados por un renombre: cuelgan de
     * book_tags por book_id, no por titulo.
     *
     * ⚠️ NOTA SOBRE goodreads_import: esa tabla matchea contra books por
     * titulo + autor (no tiene FK a books.id). Si se renombra un libro, ese
     * match se pierde para ese libro. Hoy no rompe nada porque goodreads_import
     * solo la usa un script de seed que ya se corrio una vez; si en el futuro
     * se vuelve a importar de Goodreads, tenerlo presente (el libro renombrado
     * se veria como "nuevo" y podria duplicarse).
     */
    @Transactional
    public Review updateReview(Long bookId, UpdateReviewRequest request) {

        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> bookNotFound(bookId));

        Review review = reviewRepository.findTopByBook_IdOrderByCreatedAtDesc(bookId)
                .orElseThrow(() -> reviewNotFound(bookId));

        // Titulo / autor del libro (opcionales). Va primero para que, si hay
        // colision, salga el 409 antes de tocar quotes o datos de la resenia.
        if (applyTitleAndAuthor(book, request)) {
            // Renombrado: el titulo nuevo puede traer una saga mapeada.
            attachToSagaAfterCommit(book.getId());
        }

        // Fechas de lectura del libro
        if (request.getStartReadDate() != null) {
            book.setStartReadDate(request.getStartReadDate());
        }
        if (request.getEndReadDate() != null) {
            book.setEndReadDate(request.getEndReadDate());
        }

        // Portada
        if (request.getUrlCover() != null) {
            String cover = request.getUrlCover().isBlank() ? null : request.getUrlCover();
            book.setUrlCover(cover);
            book.setHasUrlCover(cover != null);
        }

        bookRepository.save(book);

        // Datos de la review.
        //
        // null = "no tocar" para los dos campos, igual que para quotes: un PUT
        // parcial (ej. solo {"title": "..."}) NO puede pisar el puntaje ni
        // borrar lo que Camila escribio. Mandar "" SI vacia el texto, y ahi la
        // columna queda en cadena vacia (nunca null: review_text es NOT NULL).
        if (request.getRating() != null) {
            review.setRating(checkRating(request.getRating()));
        }
        if (request.getReviewText() != null) {
            review.setReviewText(request.getReviewText());
        }

        // Quotes: si vienen, se reemplazan enteras (borrar y volver a crear).
        //
        // ⚠️ El null-check envuelve tambien al clear(): antes el clear() estaba
        // afuera, asi que un PUT que NO mandara "quotes" borraba todas las
        // frases de la resenia. Con el renombre de libros eso se vuelve una
        // bomba: un PUT de solo {"title": "..."} le hubiera borrado los
        // subrayados a Camila. Ahora null/ausente = no tocar, igual que el
        // resto de los campos. Mandar "quotes": [] sigue borrandolas todas, y
        // el front de produccion SIEMPRE manda el array (edit-review-form.tsx
        // arma quotes con map+filter), asi que no cambia nada para el.
        if (request.getQuotes() != null) {
            review.getQuotes().clear();

            for (String q : request.getQuotes()) {
                if (q == null || q.isBlank()) continue;

                ReviewQuote rq = new ReviewQuote();
                rq.setReview(review);
                rq.setQuoteText(q);
                rq.setCreatedAt(OffsetDateTime.now());

                review.getQuotes().add(rq);
            }
        }

        return reviewRepository.save(review);
    }

    /**
     * Aplica el renombre de titulo/autor sobre el libro, si vino pedido.
     *
     * Reglas:
     *  - title/author ausentes o null  -> no se toca nada (compatibilidad).
     *  - presentes pero vacios/blancos -> 400.
     *  - iguales a los actuales        -> no-op silencioso (renombrar "X" a "X" anda).
     *  - chocan con OTRO libro         -> 409 con mensaje legible.
     *
     * La colision se detecta ANTES del update (consultando si existe otro libro
     * con ese par y distinto id) y ademas se captura la violacion del indice
     * unico ux_books_title_author por si hay carrera entre el chequeo y el
     * flush. Los dos caminos terminan en el mismo 409: nunca en un 500 con
     * stack trace de Hibernate.
     */
    private boolean applyTitleAndAuthor(Book book, UpdateReviewRequest request) {

        if (request == null) {
            return false;
        }

        String rawTitle = request.getTitle();
        String rawAuthor = request.getAuthor();

        // Ninguno de los dos vino: el PUT es el de siempre, no hay nada que hacer.
        if (rawTitle == null && rawAuthor == null) {
            return false;
        }

        String newTitle = rawTitle == null ? book.getTitle() : cleanBookField(rawTitle, "título");
        String newAuthor = rawAuthor == null ? book.getAuthor() : cleanBookField(rawAuthor, "autor");

        // Sin cambios reales: no es error, simplemente no hacemos nada.
        if (Objects.equals(newTitle, book.getTitle()) && Objects.equals(newAuthor, book.getAuthor())) {
            return false;
        }
        boolean titleChanged = !Objects.equals(newTitle, book.getTitle());

        Optional<Book> clash =
                bookRepository.findFirstByTitleAndAuthorAndIdNot(newTitle, newAuthor, book.getId());
        if (clash.isPresent()) {
            throw titleAuthorConflict(newTitle, newAuthor, clash.get().getId());
        }

        book.setTitle(newTitle);
        book.setAuthor(newAuthor);

        try {
            // saveAndFlush (y no save) a proposito: queremos que el INSERT/UPDATE
            // vaya a la base ACA, dentro del try, y no reciencito en el commit
            // —donde la excepcion ya caeria fuera de nuestro alcance y saldria 500.
            bookRepository.saveAndFlush(book);
        } catch (DataIntegrityViolationException e) {
            // Carrera: entre el chequeo de arriba y el flush, otro request creo
            // o renombro un libro a ese mismo (title, author).
            throw titleAuthorConflict(newTitle, newAuthor, null);
        }
        return titleChanged;
    }

    /**
     * Fase 10b: suma el libro a la saga que su titulo indica, si el armado
     * automatico ya la conoce (saga_series_keys). Ver SagaAutoService.attachToMappedSaga.
     *
     * POR QUE DESPUES DEL COMMIT (y no con un savepoint dentro del alta):
     *   - Lo prioritario es que la reseña se guarde. Corriendo en afterCommit,
     *     NINGUN problema de la saga (un lock que tarda, un error de SQL, un
     *     bug) puede tumbar ni demorar el alta: ya esta commiteada.
     *   - Con savepoint habria que mezclar el savepoint JDBC con la sesion de
     *     Hibernate del alta, y el alta quedaria esperando el FOR UPDATE de la
     *     saga si Camila la esta editando en otra pestaña.
     *   - El costo: si el server se cae justo entre el commit y este paso, el
     *     libro queda sin saga. No se pierde nada: el proximo "Armar sagas"
     *     lo suma (regla 2 del armado).
     * El fallo se loguea y se sigue: la reseña ya esta.
     */
    private void attachToSagaAfterCommit(Long bookId) {
        if (bookId == null) {
            return;
        }
        Runnable attach = () -> {
            try {
                sagaAutoService.attachToMappedSaga(bookId);
            } catch (RuntimeException e) {
                log.warn("No se pudo sumar el libro {} a su saga automatica (la reseña quedo guardada igual)",
                        bookId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    attach.run();
                }
            });
        } else {
            attach.run();
        }
    }

    /** trim + validacion de vacio y de largo (books.title/author son VARCHAR(255)). */
    private String cleanBookField(String raw, String campo) {
        if (raw == null) {
            // Solo puede pasar en el alta: en el PUT el null se filtra antes
            // (ahi null significa "no tocar este campo").
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el " + campo + " del libro");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El " + campo + " del libro no puede quedar vacío");
        }
        if (value.length() > MAX_BOOK_FIELD_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El " + campo + " del libro no puede superar los "
                            + MAX_BOOK_FIELD_LEN + " caracteres");
        }
        return value;
    }

    /**
     * 404 "no existe ese libro", para GET / PUT / DELETE de /reviews/book/{id}.
     *
     * Antes el servicio tiraba EntityNotFoundException, que Spring no mapea a
     * ningun status y terminaba en 500 ("PUT a un libro inexistente explota").
     * ResponseStatusException es el mismo patron que ya usan TagService y el
     * 400/409 del renombre: status + mensaje que viaja en el JSON gracias a
     * server.error.include-message=always.
     */
    public static ResponseStatusException bookNotFound(Long bookId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No existe ningún libro con id " + bookId);
    }

    /** 404 para el caso raro de un libro que existe pero no tiene resenia. */
    public static ResponseStatusException reviewNotFound(Long bookId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "El libro " + bookId + " no tiene ninguna reseña cargada");
    }

    /**
     * Texto de la resenia para el ALTA.
     *
     * DECISION DE PRODUCTO: una resenia SIN texto escrito es valida. Camila
     * carga libros que solo quiere puntuar (o cuya resenia escribe despues), y
     * el front ya manda `reviewText: formData.reviewText || null` cuando el
     * campo quedo vacio. Bloquearlo con un 400 seria inventar una regla que la
     * app nunca tuvo.
     *
     * Como reviews.review_text es NOT NULL en la base, el "sin texto" se guarda
     * como CADENA VACIA. Asi:
     *  - no revienta la constraint (se acabo el 500 con SQL crudo),
     *  - el front no necesita cambiar nada (ya hace `data.reviewText ?? ''`),
     *  - y queda igual que el PUT, que con "" tambien guarda cadena vacia.
     *
     * Ojo con la asimetria deliberada contra el PUT: alla `reviewText: null`
     * significa "no tocar el texto que ya estaba" (misma convencion que el
     * resto de los campos opcionales y que el fix de quotes), mientras que
     * aca, en el alta, no hay texto previo que conservar, asi que null == "".
     * En los dos casos la columna termina con un valor no nulo y ningun PUT
     * parcial puede borrar lo que Camila escribio.
     */
    private String reviewTextOrEmpty(String raw) {
        return raw == null ? "" : raw;
    }

    /**
     * reviews.rating es numeric(3,2) NOT NULL con
     * CHECK (rating >= 0 AND rating <= 5 AND rating * 4 = trunc(rating * 4)).
     */
    private BigDecimal requireRating(BigDecimal raw) {
        if (raw == null) {
            // Sin esto el INSERT reventaba la NOT NULL de reviews.rating => 500.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el puntaje de la reseña (tiene que ser un número del 0 al 5)");
        }
        return checkRating(raw);
    }

    private static final BigDecimal FOUR = BigDecimal.valueOf(4);
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);

    /**
     * Valida el CHECK de la base antes de que lo valide Postgres con un 500:
     * entre 0 y 5 y en pasos de 0.25 (0 = "sin calificar", 0.25 es el minimo
     * calificable). Devuelve el valor normalizado a escala 2, que es la de la
     * columna: asi "3.5", "3.50" y "3.500" se guardan y se devuelven igual.
     */
    private BigDecimal checkRating(BigDecimal rating) {
        // multiplicar por 4 y ver que quede entero es exactamente la regla del
        // CHECK; con BigDecimal no hay redondeo de por medio (3.3 * 4 = 13.2).
        boolean inRange = rating.signum() >= 0 && rating.compareTo(FIVE) <= 0;
        boolean isQuarter = rating.multiply(FOUR).stripTrailingZeros().scale() <= 0;
        if (!inRange || !isQuarter) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El puntaje tiene que ser un número entre 0 y 5 en pasos de 0,25 "
                            + "(ej. 3, 3.25, 3.5, 3.75); llegó " + rating.toPlainString());
        }
        // Despues del chequeo de cuartos, a escala 2 nunca hay que redondear.
        return rating.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * El filtro "N estrellas" del buscador (Fase 9). Un solo lugar que traduce
     * N a un rango, para que todos los caminos de searchReviews (con o sin
     * autor/titulo/tags) filtren EXACTAMENTE igual.
     *
     * - 0  -> rating = 0 exacto ("sin calificar").
     * - 1  -> [0.25, 2): los 0.25..0.75 caen en 1★, no en "sin calificar".
     *         0.25 es el minimo calificable (el CHECK obliga a cuartos), asi
     *         que ">= 0.25" es lo mismo que "> 0".
     * - 2..4 -> [N, N+1): 4★ incluye 4.00..4.75 (decision de Juan, 2026-10-06).
     * - 5  -> rating = 5 exacto.
     *
     * Para los exactos, max = min: la mitad "rango" de la query queda vacia.
     */
    record RatingRange(BigDecimal min, BigDecimal max, boolean exact) {}

    static RatingRange ratingRange(int stars) {
        if (stars < 0 || stars > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El filtro de puntaje tiene que ser un número entero del 0 al 5 (llegó " + stars + ")");
        }
        if (stars == 0 || stars == 5) {
            BigDecimal exact = BigDecimal.valueOf(stars).setScale(2);
            return new RatingRange(exact, exact, true);
        }
        BigDecimal min = stars == 1 ? new BigDecimal("0.25") : BigDecimal.valueOf(stars).setScale(2);
        return new RatingRange(min, BigDecimal.valueOf(stars + 1L).setScale(2), false);
    }

    /** Mismo 409 para los dos caminos: chequeo previo y violacion del indice unico. */
    private ResponseStatusException titleAuthorConflict(String title, String author, Long otherBookId) {
        String quien = otherBookId == null ? "" : " (id " + otherBookId + ")";
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Ya existe otro libro" + quien + " con el título '" + title
                        + "' y el autor '" + author + "'. Cambiá el título o el autor.");
    }

    /**
     * GET /reviews/latest
     *
     * Queries: 1 (pagina, con el libro por JOIN FETCH) + 1 (count) +
     *          1 (quotes del lote) + 1 (tags del lote) = 4 fijas,
     * sin importar el tamanio de la pagina. Antes eran 2 + 2 por resenia.
     *
     * No se usa JOIN FETCH de colecciones junto con Pageable a proposito:
     * Hibernate pagina en memoria y avisa con HHH90003004. De ahi la
     * estrategia de dos queries (ver hydrate()).
     */
    @Transactional(readOnly = true)
    public Page<Review> getLatestReviews(Pageable pageable) {
        Page<Review> page = reviewRepository.findAllWithBook(pageable);
        hydrate(page.getContent());
        return page;
    }

    @Transactional(readOnly = true)
    public Optional<Review> getReviewByBookId(Long bookId) {
        Optional<Review> review = reviewRepository.findTopByBook_IdOrderByCreatedAtDesc(bookId);
        review.ifPresent(r -> hydrate(List.of(r)));
        return review;
    }

    /**
     * Igual que getReviewByBookId pero tirando el 404 con mensaje, para que
     * GET, PUT y DELETE de /reviews/book/{id} contesten los tres lo mismo ante
     * un id que no existe (antes el GET daba un 404 con el body vacio y el PUT
     * y el DELETE directamente 500).
     *
     * El existsById extra solo se ejecuta en el camino de error, para poder
     * distinguir "ese libro no existe" de "existe pero no tiene resenia".
     */
    @Transactional(readOnly = true)
    public Review getReviewByBookIdOrFail(Long bookId) {
        return getReviewByBookId(bookId)
                .orElseThrow(() -> bookRepository.existsById(bookId)
                        ? reviewNotFound(bookId)
                        : bookNotFound(bookId));
    }

    @Transactional(readOnly = true)
    public List<Review> getReviewsByBookId(Long bookId) {
        List<Review> reviews = reviewRepository.findByBook_IdOrderByCreatedAtDesc(bookId);
        hydrate(reviews);
        return reviews;
    }

    /**
     * Inicializa quotes y tags de un lote de resenias en una cantidad FIJA de
     * queries (chico: 2 por lote de 500), en vez de una por resenia/libro.
     * Los objetos ya estan en el persistence context; estas queries solo les
     * llenan las colecciones antes de que Jackson las serialice.
     */
    private void hydrate(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }

        List<Long> reviewIds = reviews.stream()
                .map(Review::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        for (int i = 0; i < reviewIds.size(); i += BATCH) {
            reviewRepository.fetchQuotesForReviews(
                    reviewIds.subList(i, Math.min(i + BATCH, reviewIds.size())));
        }

        // getId() sobre el proxy del libro no lo inicializa; la query de tags
        // trae el libro entero y de paso resuelve el proxy.
        List<Long> bookIds = reviews.stream()
                .map(Review::getBook)
                .filter(Objects::nonNull)
                .map(Book::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        tagService.prefetchTags(bookIds);
    }

    /** Firma vieja, por compatibilidad: busca sin filtro de tags. */
    public List<Review> searchReviews(
            String author,
            String bookTitle,
            Integer rating,
            OffsetDateTime reviewFrom,
            OffsetDateTime reviewTo,
            OffsetDateTime readFrom,
            OffsetDateTime readTo
    ) {
        return searchReviews(author, bookTitle, rating, reviewFrom, reviewTo, readFrom, readTo, null, false);
    }

    @Transactional(readOnly = true)
    public List<Review> searchReviews(
            String author,
            String bookTitle,
            Integer rating,
            OffsetDateTime reviewFrom,
            OffsetDateTime reviewTo,
            OffsetDateTime readFrom,
            OffsetDateTime readTo,
            List<Long> tagIds,
            boolean tagModeAll
    ) {

        boolean hasAuthor   = author != null && !author.isBlank();
        boolean hasBookName = bookTitle != null && !bookTitle.isBlank();
        boolean hasRating   = rating != null;
        boolean hasTags     = tagIds != null && !tagIds.isEmpty();

        // El rango se calcula (y se valida, 400 si N no es 0..5) ANTES de
        // cualquier query, incluida la de tags.
        RatingRange range = hasRating ? ratingRange(rating) : null;

        // 0) Filtro por tags: una sola query sobre book_tags (tabla chica),
        //    que devuelve los book_id que matchean.
        Set<Long> allowedBookIds = null;
        if (hasTags) {
            allowedBookIds = new HashSet<>(tagService.bookIdsForTags(tagIds, tagModeAll));
            if (allowedBookIds.isEmpty()) {
                return List.of();   // ningun libro tiene esos tags
            }
        }

        // 1) Filtro base por autor / título / rango de rating
        List<Review> base;

        if (hasTags && !hasAuthor && !hasBookName && !hasRating) {
            // Solo tags: vamos derecho por book_id en vez de traer las 1946 resenias.
            base = reviewRepository.findByBook_IdInOrderByCreatedAtDesc(allowedBookIds);
        } else if (hasAuthor && hasBookName && hasRating) {
            base = reviewRepository.findByAuthorAndBookTitleAndRatingRange(
                    author, bookTitle, range.min(), range.max(), range.exact());
        } else if (hasAuthor && hasRating) {
            base = reviewRepository.findByAuthorAndRatingRange(
                    author, range.min(), range.max(), range.exact());
        } else if (hasBookName && hasRating) {
            base = reviewRepository.findByTitleAndRatingRange(
                    bookTitle, range.min(), range.max(), range.exact());
        } else if (hasRating) {
            base = reviewRepository.findByRatingRange(range.min(), range.max(), range.exact());
        } else if (hasAuthor) {
            base = reviewRepository.findByBook_AuthorContainingIgnoreCase(author);
        } else if (hasBookName) {
            base = reviewRepository.findByBook_TitleContainingIgnoreCase(bookTitle);
        } else {
            base = reviewRepository.findAll();
        }

        // 1.b) Si el filtro de tags se combina con los otros, se aplica encima.
        if (hasTags && !base.isEmpty()) {
            final Set<Long> allowed = allowedBookIds;
            base = base.stream()
                    .filter(r -> r.getBook() != null
                              && r.getBook().getId() != null
                              && allowed.contains(r.getBook().getId()))
                    .collect(Collectors.toList());
        }

        // 1.c) Traemos quotes + tags en lote ANTES de filtrar por fechas
        //      (el filtro de fechas toca book.startReadDate y sin esto
        //       dispararia un SELECT por libro).
        hydrate(base);

        // 2) Filtro por fechas de lectura (start/end del BOOK)
        if (readFrom == null && readTo == null) {
            return base; // sin filtro de fechas
        }

        return base.stream()
                .filter(review -> {
                    OffsetDateTime start = review.getBook().getStartReadDate();
                    OffsetDateTime end   = review.getBook().getEndReadDate();

                    // --- Caso A: SOLO readFrom ---
                    if (readFrom != null && readTo == null) {
                        // Queremos: startReadDate >= readFrom
                        if (start == null) return false;
                        return !start.isBefore(readFrom); // start >= readFrom
                    }

                    // --- Caso B: SOLO readTo ---
                    if (readFrom == null && readTo != null) {
                        // Queremos: endReadDate <= readTo
                        // Si no tenemos fecha de fin, no sabemos si terminó → lo excluimos
                        if (end == null) return false;
                        return !end.isAfter(readTo); // end <= readTo
                    }

                    // --- Caso C: readFrom Y readTo ---
                    if (readFrom != null && readTo != null) {
                        // Queremos libro COMPLETAMENTE entre el rango:
                        // start >= readFrom  AND  end <= readTo
                        if (start == null || end == null) return false;
                        if (start.isBefore(readFrom)) return false; // start < readFrom → afuera
                        if (end.isAfter(readTo)) return false;      // end > readTo → afuera
                        return true;
                    }

                    // fallback (no deberíamos llegar acá)
                    return true;
                })
                .collect(Collectors.toList());
    }
}
