package net.casapipis.camireads.dto.saga;

/** GET /books/{bookId}/sagas: en que saga esta el libro y en que lugar. */
public record BookSaga(Long id, String name, int position, long bookCount) {
}
