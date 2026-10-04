package net.casapipis.camireads.web.projection;

/** Un autor y cuantos libros suyos hay cargados. */
public interface AuthorCountView {
    String getAuthor();
    long getAmount();
}
