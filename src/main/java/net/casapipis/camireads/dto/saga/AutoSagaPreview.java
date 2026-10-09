package net.casapipis.camireads.dto.saga;

import java.util.List;

/**
 * GET /sagas/auto/preview: lo que haria "Armar sagas automaticamente", sin
 * tocar nada. POST /sagas/auto/apply hace EXACTAMENTE esto (mismo calculo).
 *
 * create: sagas nuevas (una por saga del titulo con 2 o mas libros).
 * extend: sagas que ya existen y suman libros al final.
 * totalBooks: libros que se agregarian en total (create + extend).
 * skippedDismissed: sagas del titulo que Camila borro (clave descartada) y
 *   que por eso el armado NO vuelve a crear ni extender.
 */
public record AutoSagaPreview(
        List<Create> create,
        List<Extend> extend,
        int totalBooks,
        int skippedDismissed
) {

    public record Create(String name, int bookCount, List<Book> books) {
    }

    public record Extend(Long sagaId, String name, int addCount, List<Book> books) {
    }

    public record Book(Long bookId, String title) {
    }
}
