package net.casapipis.camireads.dto.saga;

import lombok.Data;

/**
 * Body de PUT /sagas/{id}. Parcial: null o ausente (o en blanco) = no tocar.
 *  - name         -> renombra (y recalcula el slug; 409 si choca con otra saga)
 *  - urlCover     -> pone el link y BORRA la foto
 *  - coverDataUrl -> pone la foto y BORRA el link
 *  - clearCover   -> true borra las dos (la saga vuelve al collage)
 */
@Data
public class UpdateSagaRequest {
    private String name;
    private String urlCover;
    private String coverDataUrl;
    private Boolean clearCover;
}
