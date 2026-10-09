package net.casapipis.camireads.dto.saga;

import lombok.Data;

/** Body de POST /sagas/{targetId}/merge: la saga que se absorbe (y se borra). */
@Data
public class MergeSagaRequest {
    private Long fromSagaId;
}
