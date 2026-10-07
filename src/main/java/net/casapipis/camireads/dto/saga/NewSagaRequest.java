package net.casapipis.camireads.dto.saga;

import lombok.Data;

/**
 * Body de POST /sagas. Imagen opcional: link O foto (data URL), nunca las dos.
 * Un urlCover/coverDataUrl en blanco cuenta como "no mandado".
 */
@Data
public class NewSagaRequest {
    private String name;
    private String urlCover;
    private String coverDataUrl;
}
