package net.casapipis.camireads.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.model.Book;
import net.casapipis.camireads.domain.model.Review;
import net.casapipis.camireads.domain.model.ReviewQuote;
import net.casapipis.camireads.domain.repository.BookRepository;
import net.casapipis.camireads.domain.repository.ReviewRepository;
import net.casapipis.camireads.dto.UpdateReviewRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final ReviewRepository reviewRepository;
    private final BookRepository bookRepository;
    private final TagService tagService;

    @Transactional
    public Review createReviewForNewBook(NewReviewRequest request) {

        // Zona horaria Argentina
        ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");
        OffsetDateTime nowAr = OffsetDateTime.now(zone);

        // 1) Crear Book
        Book book = new Book();
        book.setTitle(request.getTitle());
        book.setAuthor(request.getAuthor());

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

        bookRepository.save(book);

        // 2) Crear Review
        Review review = new Review();
        review.setBook(book);
        review.setRating(request.getRating());
        review.setReviewText(request.getReviewText());
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
                .orElseThrow(() -> new EntityNotFoundException("Book not found: " + bookId));

        // Borramos TODAS las reviews asociadas a ese libro
        // (por si en el futuro permitís más de una)
        var reviews = reviewRepository.findByBook_IdOrderByCreatedAtDesc(bookId);
        if (!reviews.isEmpty()) {
            reviewRepository.deleteAll(reviews);
        }

        // Y por último el libro
        bookRepository.delete(book);
    }

    @Transactional
    public Review updateReview(Long bookId, UpdateReviewRequest request) {

        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new EntityNotFoundException("Book not found: " + bookId));

        Review review = reviewRepository.findTopByBook_IdOrderByCreatedAtDesc(bookId)
                .orElseThrow(() -> new EntityNotFoundException("Review not found for book: " + bookId));

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

        // Datos de la review
        if (request.getRating() != null) {
            review.setRating(request.getRating());
        }
        if (request.getReviewText() != null) {
            review.setReviewText(request.getReviewText());
        }

        // Quotes: borramos todas y volvemos a crear
        review.getQuotes().clear();

        if (request.getQuotes() != null) {
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
