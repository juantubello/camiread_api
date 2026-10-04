package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
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
import org.springframework.web.server.ResponseStatusException;
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
        applyTitleAndAuthor(book, request);

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
            review.setRating(checkRatingRange(request.getRating()));
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
    private void applyTitleAndAuthor(Book book, UpdateReviewRequest request) {

        if (request == null) {
            return;
        }

        String rawTitle = request.getTitle();
        String rawAuthor = request.getAuthor();

        // Ninguno de los dos vino: el PUT es el de siempre, no hay nada que hacer.
        if (rawTitle == null && rawAuthor == null) {
            return;
        }

        String newTitle = rawTitle == null ? book.getTitle() : cleanBookField(rawTitle, "título");
        String newAuthor = rawAuthor == null ? book.getAuthor() : cleanBookField(rawAuthor, "autor");

        // Sin cambios reales: no es error, simplemente no hacemos nada.
        if (Objects.equals(newTitle, book.getTitle()) && Objects.equals(newAuthor, book.getAuthor())) {
            return;
        }

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

    /** reviews.rating es NOT NULL con CHECK (rating >= 0 AND rating <= 5). */
    private int requireRating(Integer raw) {
        if (raw == null) {
            // Sin esto era un NullPointerException al desempaquetar el Integer
            // en el int de Review.rating => 500 pelado.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el puntaje de la reseña (tiene que ser un número del 0 al 5)");
        }
        return checkRatingRange(raw);
    }

    /** Valida el CHECK de la base antes de que lo valide Postgres con un 500. */
    private int checkRatingRange(int rating) {
        if (rating < 0 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El puntaje tiene que estar entre 0 y 5 (llegó " + rating + ")");
        }
        return rating;
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

        // 0) Filtro por tags: una sola query sobre book_tags (tabla chica),
        //    que devuelve los book_id que matchean.
        Set<Long> allowedBookIds = null;
        if (hasTags) {
            allowedBookIds = new HashSet<>(tagService.bookIdsForTags(tagIds, tagModeAll));
            if (allowedBookIds.isEmpty()) {
                return List.of();   // ningun libro tiene esos tags
            }
        }

        // 1) Filtro base por autor / título / rating (igual que antes)
        List<Review> base;

        if (hasTags && !hasAuthor && !hasBookName && !hasRating) {
            // Solo tags: vamos derecho por book_id en vez de traer las 1946 resenias.
            base = reviewRepository.findByBook_IdInOrderByCreatedAtDesc(allowedBookIds);
        } else if (hasAuthor && hasBookName && hasRating) {
            base = reviewRepository.findByAuthorAndBookTitleAndRating(author, bookTitle, rating);
        } else if (hasAuthor && hasRating) {
            base = reviewRepository.findByBook_AuthorContainingIgnoreCaseAndRating(author, rating);
        } else if (hasBookName && hasRating) {
            base = reviewRepository.findByBook_TitleContainingIgnoreCaseAndRating(bookTitle, rating);
        } else if (hasRating) {
            base = reviewRepository.findByRating(rating);
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
