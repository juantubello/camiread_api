package net.casapipis.camireads.dto;

import lombok.Data;

/**
 * Body de PUT /tags/{id}. Los dos campos son opcionales:
 *  - name != null  -> renombra (y recalcula el slug)
 *  - color != null -> recolorea ("" o en blanco borra el color)
 */
@Data
public class UpdateTagRequest {
    private String name;
    private String color;
}
