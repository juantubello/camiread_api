package net.casapipis.camireads.web.controller;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.saga.AddSagaBookRequest;
import net.casapipis.camireads.dto.saga.NewSagaRequest;
import net.casapipis.camireads.dto.saga.SagaDetail;
import net.casapipis.camireads.dto.saga.SagaSummary;
import net.casapipis.camireads.dto.saga.SetSagaOrderRequest;
import net.casapipis.camireads.dto.saga.UpdateSagaRequest;
import net.casapipis.camireads.service.SagaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mis sagas (Fase 10). Toda la logica y las validaciones estan en SagaService.
 * Las respuestas de escritura devuelven la saga entera (SagaDetail) para que
 * el front refresque la pantalla sin un GET extra.
 *
 * El @CrossOrigin es el mismo que TagController (el front en dev pega directo
 * al backend desde otro origen, y necesita el preflight de PUT/POST/DELETE).
 */
@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)
@RestController
@RequestMapping("/sagas")
@RequiredArgsConstructor
public class SagaController {

    private final SagaService sagaService;

    /** GET /sagas -> SagaSummary[] (ultima modificada primero). */
    @GetMapping
    public List<SagaSummary> list() {
        return sagaService.list();
    }

    /** POST /sagas {name, urlCover?, coverDataUrl?} -> 201 SagaDetail. */
    @PostMapping
    public ResponseEntity<SagaDetail> create(@RequestBody(required = false) NewSagaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sagaService.create(request));
    }

    /** GET /sagas/{id} -> SagaDetail con los libros en orden. */
    @GetMapping("/{id}")
    public SagaDetail get(@PathVariable long id) {
        return sagaService.get(id);
    }

    /** PUT /sagas/{id} {name?, urlCover?, coverDataUrl?, clearCover?} -> SagaDetail. */
    @PutMapping("/{id}")
    public SagaDetail update(@PathVariable long id, @RequestBody(required = false) UpdateSagaRequest request) {
        return sagaService.update(id, request);
    }

    /** DELETE /sagas/{id} -> 204. Los libros no se borran. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        sagaService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** POST /sagas/{id}/books {bookId} -> agrega al final -> SagaDetail. */
    @PostMapping("/{id}/books")
    public SagaDetail addBook(@PathVariable long id, @RequestBody(required = false) AddSagaBookRequest request) {
        return sagaService.addBook(id, request == null ? null : request.getBookId());
    }

    /** DELETE /sagas/{id}/books/{bookId} -> saca el libro y renumera -> SagaDetail. */
    @DeleteMapping("/{id}/books/{bookId}")
    public SagaDetail removeBook(@PathVariable long id, @PathVariable long bookId) {
        return sagaService.removeBook(id, bookId);
    }

    /** PUT /sagas/{id}/order {bookIds} -> posiciones 1..n en ese orden -> SagaDetail. */
    @PutMapping("/{id}/order")
    public SagaDetail reorder(@PathVariable long id, @RequestBody(required = false) SetSagaOrderRequest request) {
        return sagaService.reorder(id, request == null ? null : request.getBookIds());
    }
}
