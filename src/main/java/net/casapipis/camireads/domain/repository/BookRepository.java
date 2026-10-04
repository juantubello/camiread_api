package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * Busca OTRO libro (id distinto) que ya tenga ese par (title, author).
     *
     * Existe para poder detectar la colision ANTES de escribir y contestar un
     * 409 legible, en vez de dejar que reviente el indice unico de la base
     * ("ux_books_title_author" UNIQUE, btree (title, author)) y salga un 500
     * con stack trace de Hibernate.
     *
     * Ojo: la comparacion es exacta (case y espacios incluidos), igual que el
     * indice unico de Postgres. Si el indice algun dia pasa a ser case
     * insensitive, esta query tiene que acompaniar.
     */
    Optional<Book> findFirstByTitleAndAuthorAndIdNot(String title, String author, Long id);

    /**
     * Igual que la de arriba pero para el ALTA (POST /reviews), donde todavia no
     * hay id propio al que excluir: si ya existe un libro con ese par, el insert
     * choca contra ux_books_title_author. Chequeandolo antes contestamos 409 con
     * un mensaje legible en vez de un 500 con el SQL crudo.
     */
    Optional<Book> findFirstByTitleAndAuthor(String title, String author);

    /**
     * Trae los tags de un lote de libros en UNA query.
     *
     * Se usa como segunda query del patron "dos queries": los Book ya estan en
     * el persistence context (vinieron con la pagina de reviews) y esta llamada
     * les inicializa la coleccion 'tags' de una. Sin esto, Jackson dispararia
     * un SELECT por libro al serializar (N+1).
     *
     * Ojo: NO se puede mezclar este JOIN FETCH con Pageable (Hibernate pagina en
     * memoria y avisa con HHH90003004). Por eso va aparte, sin paginacion.
     */
    @Query("select distinct b from Book b left join fetch b.tags where b.id in :ids")
    List<Book> fetchTagsForBooks(@Param("ids") Collection<Long> ids);
}
