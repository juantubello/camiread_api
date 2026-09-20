package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookRepository extends JpaRepository<Book, Long> {

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
