package net.casapipis.camireads.dto.saga;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /sagas/{id}: lo mismo que SagaSummary + los libros ordenados por posicion.
 * autoDetected va al final para no correr el orden de los campos de la Fase 10.
 */
public record SagaDetail(
        Long id,
        String name,
        String urlCover,
        String coverDataUrl,
        long bookCount,
        List<String> previewCovers,
        OffsetDateTime updatedAt,
        List<SagaBook> books,
        boolean autoDetected
) {
}
