package net.casapipis.camireads.dto.saga;

import java.util.List;

/**
 * POST /sagas/auto/apply.
 *
 * skippedConflicts: nombres de sagas nuevas que no se pudieron crear porque
 * su slug ya lo tenia otra saga (no deberia pasar: si existe una saga con ese
 * nombre se extiende en vez de crear; solo puede aparecer por una carrera).
 */
public record AutoSagaApplyResult(int created, int extended, int booksAdded, List<String> skippedConflicts) {
}
