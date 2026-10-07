package net.casapipis.camireads.web.controller;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.saga.BookSaga;
import net.casapipis.camireads.service.SagaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Rutas que cuelgan del LIBRO (no de la resenia). Hoy solo la de sagas; lo
 * demas de los libros sigue viviendo en /reviews/book/{bookId}.
 */
@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.OPTIONS
        }
)
@RestController
@RequestMapping("/books")
@RequiredArgsConstructor
public class BookController {

    private final SagaService sagaService;

    /** GET /books/{bookId}/sagas -> BookSaga[] (404 si el libro no existe). */
    @GetMapping("/{bookId}/sagas")
    public List<BookSaga> sagas(@PathVariable long bookId) {
        return sagaService.sagasOfBook(bookId);
    }
}
