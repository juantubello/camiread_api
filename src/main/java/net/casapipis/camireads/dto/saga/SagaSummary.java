package net.casapipis.camireads.dto.saga;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Item de GET /sagas.
 *
 * coverDataUrl: "data:<mime>;base64,<b64>" armado por el backend (el front no
 * tiene que saber como se guarda la foto), o null si la saga usa link o no
 * tiene imagen.
 *
 * previewCovers: hasta 4 tapas de los primeros libros, en orden de posicion,
 * para que el front arme un collage cuando la saga no tiene imagen propia.
 */
public record SagaSummary(
        Long id,
        String name,
        String urlCover,
        String coverDataUrl,
        long bookCount,
        List<String> previewCovers,
        OffsetDateTime updatedAt
) {
}
