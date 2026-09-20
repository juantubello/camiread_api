package net.casapipis.camireads.service;

import net.casapipis.camireads.domain.model.Tag;
import net.casapipis.camireads.domain.repository.TagRepository;
import net.casapipis.camireads.dto.NewTagRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests contra una base REAL (la copia sandbox en 127.0.0.1:5434).
 * Se saltean solos si no hay datasource configurado, asi que el build
 * sigue andando sin base.
 *
 * Todo lo que crean lo crean ellos mismos y lo limpian: no tocan los
 * 5 tags sembrados ni ningun libro/resenia existente.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class TagServiceDbTest {

    @Autowired
    private TagService tagService;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? -1 : n;
    }

    @Test
    @DisplayName("BORRAR UN TAG NO BORRA LIBROS NI RESENIAS (se cuentan antes y despues)")
    void borrarTagNoBorraLibrosNiResenias() {
        long booksBefore   = count("books");
        long reviewsBefore = count("reviews");
        long quotesBefore  = count("review_quotes");
        long linksBefore   = count("book_tags");

        assertTrue(booksBefore > 0, "la base de prueba tiene que tener libros");

        // 1) Tag descartable, con nombre unico para no pisar nada.
        NewTagRequest req = new NewTagRequest();
        req.setName("Zz Test Borrado " + UUID.randomUUID().toString().substring(0, 8));
        req.setColor("#123456");
        Tag temp = tagService.createOrGet(req).tag();
        assertNotNull(temp.getId());

        // 2) Lo colgamos de 3 libros reales escribiendo SOLO en book_tags
        //    (no usamos setBookTags para no pisarles los tags que ya tienen).
        List<Long> someBooks = jdbc.queryForList(
                "SELECT id FROM books ORDER BY id LIMIT 3", Long.class);
        for (Long bookId : someBooks) {
            jdbc.update("INSERT INTO book_tags (book_id, tag_id) VALUES (?, ?)", bookId, temp.getId());
        }
        assertEquals(linksBefore + someBooks.size(), count("book_tags"));

        // 3) El borrado bajo prueba.
        tagService.delete(temp.getId());

        // 4) Lo unico que tiene que haber cambiado son las filas del tag.
        assertEquals(booksBefore,   count("books"),         "¡SE BORRARON LIBROS!");
        assertEquals(reviewsBefore, count("reviews"),       "¡SE BORRARON RESEÑAS!");
        assertEquals(quotesBefore,  count("review_quotes"), "¡SE BORRARON FRASES!");
        assertEquals(linksBefore,   count("book_tags"),     "quedaron o faltan filas en book_tags");
        assertTrue(tagRepository.findById(temp.getId()).isEmpty(), "el tag deberia estar borrado");

        // 5) Y los libros que estaban asociados siguen ahi.
        for (Long bookId : someBooks) {
            Long stillThere = jdbc.queryForObject(
                    "SELECT count(*) FROM books WHERE id = ?", Long.class, bookId);
            assertEquals(1L, stillThere, "el libro " + bookId + " tiene que seguir existiendo");
        }
    }

    @Test
    @DisplayName("POST /tags es idempotente por slug: 'Tengo en físico' reusa el tag sembrado")
    void createOrGetEsIdempotentePorSlug() {
        long tagsBefore = count("tags");

        Tag sembrado = tagRepository.findBySlug("tengo-en-fisico").orElse(null);
        if (sembrado == null) {
            return; // base sin el seed: no hay nada que probar
        }

        NewTagRequest conTilde = new NewTagRequest();
        conTilde.setName("Tengo en físico");
        TagService.CreateResult r1 = tagService.createOrGet(conTilde);

        NewTagRequest sinTilde = new NewTagRequest();
        sinTilde.setName("  TENGO EN FISICO  ");
        TagService.CreateResult r2 = tagService.createOrGet(sinTilde);

        assertEquals(sembrado.getId(), r1.tag().getId(), "tendria que devolver el tag ya sembrado");
        assertEquals(sembrado.getId(), r2.tag().getId(), "tendria que devolver el tag ya sembrado");
        assertTrue(!r1.created() && !r2.created(), "no tendria que haber creado nada");
        assertEquals(tagsBefore, count("tags"), "no se tienen que crear tags duplicados");
    }
}
