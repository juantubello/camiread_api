package net.casapipis.camireads.web.projection;

/**
 * Proyeccion de solo lectura para GET /tags: el tag + cuantos libros lo tienen.
 * La llena la query agregada de TagRepository.findAllWithBookCount().
 */
public interface TagCountView {
    Long getId();
    String getName();
    String getSlug();
    String getColor();
    long getBookCount();
}
