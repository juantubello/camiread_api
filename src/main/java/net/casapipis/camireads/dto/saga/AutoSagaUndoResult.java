package net.casapipis.camireads.dto.saga;

/** POST /sagas/auto/undo: cuantas sagas se borraron. */
public record AutoSagaUndoResult(int deleted) {
}
