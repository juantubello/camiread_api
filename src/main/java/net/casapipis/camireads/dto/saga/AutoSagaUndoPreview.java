package net.casapipis.camireads.dto.saga;

/** GET /sagas/auto/undo-preview: cuantas sagas borraria el deshacer. */
public record AutoSagaUndoPreview(int count) {
}
