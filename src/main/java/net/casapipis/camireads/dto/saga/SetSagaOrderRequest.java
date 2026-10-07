package net.casapipis.camireads.dto.saga;

import lombok.Data;

import java.util.List;

/** Body de PUT /sagas/{id}/order: TODOS los libros de la saga, en el orden nuevo. */
@Data
public class SetSagaOrderRequest {
    private List<Long> bookIds;
}
