package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.model.Book;
import net.casapipis.camireads.domain.model.Tag;
import net.casapipis.camireads.domain.repository.BookRepository;
import net.casapipis.camireads.domain.repository.TagRepository;
import net.casapipis.camireads.dto.BookTagsResponse;
import net.casapipis.camireads.dto.NewTagRequest;
import net.casapipis.camireads.dto.SetBookTagsRequest;
import net.casapipis.camireads.dto.TagResponse;
import net.casapipis.camireads.dto.TagWithCountResponse;
import net.casapipis.camireads.dto.UpdateTagRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TagService {

    /** tags.name y tags.slug son VARCHAR(60) en la base. */
    private static final int MAX_LEN = 60;

    /** Los colores sembrados son hex #RRGGBB; aceptamos tambien la forma corta. */
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    /** Lotes para los IN (...) de las queries de prefetch. */
    private static final int BATCH = 500;

    private final TagRepository tagRepository;
    private final BookRepository bookRepository;

    /** Resultado de un alta idempotente: el tag, y si hubo que crearlo o ya estaba. */
    public record CreateResult(Tag tag, boolean created) {
    }

    // ─────────────────────────────────────────────────────────────
    // Lecturas
    // ─────────────────────────────────────────────────────────────

    /** GET /tags — una sola query agregada, ordenada por nombre. */
    @Transactional(readOnly = true)
    public List<TagWithCountResponse> listWithBookCount() {
        return tagRepository.findAllWithBookCount().stream()
                .map(v -> new TagWithCountResponse(
                        v.getId(), v.getName(), v.getSlug(), v.getColor(), v.getBookCount()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // Altas / modificaciones
    // ─────────────────────────────────────────────────────────────

    /**
     * POST /tags — IDEMPOTENTE POR SLUG.
     * Si ya hay un tag con ese slug devuelve el existente en vez de reventar
     * contra el indice unico. Es lo que hace que escribir "Tengo en fisico"
     * (con o sin tilde) reuse el tag ya sembrado.
     */
    @Transactional
    public CreateResult createOrGet(NewTagRequest request) {
        String name = cleanName(request == null ? null : request.getName());
        String slug = slugOrFail(name);
        String color = cleanColor(request == null ? null : request.getColor());

        Optional<Tag> existing = tagRepository.findBySlug(slug);
        if (existing.isPresent()) {
            return new CreateResult(existing.get(), false);
        }

        Tag tag = new Tag();
        tag.setName(name);
        tag.setSlug(slug);
        tag.setColor(color);

        try {
            return new CreateResult(tagRepository.saveAndFlush(tag), true);
        } catch (DataIntegrityViolationException e) {
            // Carrera con otro request: alguien lo creo entre el find y el insert.
            return new CreateResult(
                    tagRepository.findBySlug(slug).orElseThrow(() -> e),
                    false);
        }
    }

    /** PUT /tags/{id} — renombrar y/o recolorear. 409 si el slug nuevo choca con OTRO tag. */
    @Transactional
    public Tag update(Long id, UpdateTagRequest request) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found: " + id));

        if (request != null && request.getName() != null) {
            String name = cleanName(request.getName());
            String slug = slugOrFail(name);

            Optional<Tag> clash = tagRepository.findBySlug(slug);
            if (clash.isPresent() && !clash.get().getId().equals(tag.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Ya existe otro tag con el slug '" + slug + "' (id " + clash.get().getId() + ")");
            }

            tag.setName(name);
            tag.setSlug(slug);
        }

        if (request != null && request.getColor() != null) {
            // color: "" o en blanco = borrar el color
            tag.setColor(cleanColor(request.getColor()));
        }

        return tagRepository.save(tag);
    }

    /**
     * DELETE /tags/{id} — borra el tag y SOLO sus filas de book_tags.
     * Ninguna de las dos sentencias nombra books ni reviews: es imposible que
     * este metodo borre un libro o una resenia.
     */
    @Transactional
    public int delete(Long id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found: " + id));

        int unlinked = tagRepository.deleteBookTagsByTagId(tag.getId());
        tagRepository.delete(tag);
        return unlinked;
    }

    /**
     * PUT /reviews/book/{bookId}/tags — reemplaza el conjunto de tags del libro.
     * Los newTagNames se crean, o se reusan si el slug ya existe.
     */
    @Transactional
    public BookTagsResponse setBookTags(Long bookId, SetBookTagsRequest request) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found: " + bookId));

        Set<Tag> target = new LinkedHashSet<>();

        if (request != null && request.getTagIds() != null) {
            for (Long tagId : request.getTagIds()) {
                if (tagId == null) continue;
                Tag tag = tagRepository.findById(tagId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Tag not found: " + tagId));
                target.add(tag);
            }
        }

        if (request != null && request.getNewTagNames() != null) {
            for (String rawName : request.getNewTagNames()) {
                if (rawName == null || rawName.isBlank()) continue;
                NewTagRequest req = new NewTagRequest();
                req.setName(rawName);
                target.add(createOrGet(req).tag());
            }
        }

        // Reemplazo del conjunto: Hibernate solo toca book_tags.
        book.getTags().clear();
        book.getTags().addAll(target);
        bookRepository.save(book);

        List<TagResponse> tags = target.stream()
                .sorted(Comparator.comparing(t -> t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT)))
                .map(TagResponse::from)
                .toList();

        return new BookTagsResponse(book.getId(), tags);
    }

    // ─────────────────────────────────────────────────────────────
    // Soporte para el filtro de GET /reviews
    // ─────────────────────────────────────────────────────────────

    /**
     * book_ids que matchean el filtro de tags.
     * Una sola query, sobre la tabla puente (que es chica), nunca sobre books.
     */
    @Transactional(readOnly = true)
    public List<Long> bookIdsForTags(Collection<Long> tagIds, boolean modeAll) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>(tagIds);
        return modeAll
                ? tagRepository.findBookIdsWithAllTags(distinct, distinct.size())
                : tagRepository.findBookIdsWithAnyTag(distinct);
    }

    /**
     * Inicializa la coleccion 'tags' de un lote de libros en 1 query por lote.
     * Los Book ya estan en el persistence context, asi que esta llamada les
     * llena la coleccion y Jackson despues no dispara nada. Anti N+1.
     */
    @Transactional(readOnly = true)
    public void prefetchTags(Collection<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(bookIds));
        for (int i = 0; i < ids.size(); i += BATCH) {
            bookRepository.fetchTagsForBooks(ids.subList(i, Math.min(i + BATCH, ids.size())));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Validacion / limpieza
    // ─────────────────────────────────────────────────────────────

    private String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del tag no puede estar vacio");
        }
        String name = raw.trim().replaceAll("\\s{2,}", " ");
        if (name.length() > MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre del tag no puede superar los " + MAX_LEN + " caracteres");
        }
        return name;
    }

    private String slugOrFail(String name) {
        String slug = TagSlugNormalizer.toSlug(name);
        if (slug.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre '" + name + "' no produce un slug valido");
        }
        if (slug.length() > MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El slug resultante supera los " + MAX_LEN + " caracteres");
        }
        return slug;
    }

    private String cleanColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String color = raw.trim();
        if (!HEX_COLOR.matcher(color).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El color debe ser hexadecimal, por ejemplo #C77D6A (llego: '" + color + "')");
        }
        return color;
    }
}
