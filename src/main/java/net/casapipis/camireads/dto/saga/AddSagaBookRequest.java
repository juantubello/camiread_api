package net.casapipis.camireads.dto.saga;

import lombok.Data;

/** Body de POST /sagas/{id}/books. */
@Data
public class AddSagaBookRequest {
    private Long bookId;
}
