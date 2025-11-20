package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {
    // por ahora no agregamos métodos custom
}
