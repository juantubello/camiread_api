package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Tag;
import net.casapipis.camireads.web.projection.TagCountView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findBySlug(String slug);

    List<Tag> findAllByOrderByNameAsc();

    /**
     * Listado de tags con la cantidad de libros de cada uno en UNA sola query agregada.
     * (Nada de una query por tag.)
     */
    @Query(value = """
            SELECT t.id          AS id,
                   t.name        AS name,
                   t.slug        AS slug,
                   t.color       AS color,
                   COUNT(bt.book_id) AS bookCount
            FROM tags t
            LEFT JOIN book_tags bt ON bt.tag_id = t.id
            GROUP BY t.id, t.name, t.slug, t.color
            ORDER BY t.name ASC
            """, nativeQuery = true)
    List<TagCountView> findAllWithBookCount();

    /** book_ids que tienen AL MENOS uno de los tags pedidos (modo "any"). */
    @Query(value = """
            SELECT DISTINCT bt.book_id
            FROM book_tags bt
            WHERE bt.tag_id IN (:tagIds)
            """, nativeQuery = true)
    List<Long> findBookIdsWithAnyTag(@Param("tagIds") Collection<Long> tagIds);

    /** book_ids que tienen TODOS los tags pedidos (modo "all"). */
    @Query(value = """
            SELECT bt.book_id
            FROM book_tags bt
            WHERE bt.tag_id IN (:tagIds)
            GROUP BY bt.book_id
            HAVING COUNT(DISTINCT bt.tag_id) = :howMany
            """, nativeQuery = true)
    List<Long> findBookIdsWithAllTags(@Param("tagIds") Collection<Long> tagIds,
                                      @Param("howMany") long howMany);

    /**
     * Borra SOLO las filas de la tabla puente de ese tag.
     * Jamas toca books ni reviews (la sentencia nombra unicamente book_tags).
     */
    @Modifying
    @Query(value = "DELETE FROM book_tags WHERE tag_id = :tagId", nativeQuery = true)
    int deleteBookTagsByTagId(@Param("tagId") Long tagId);
}
