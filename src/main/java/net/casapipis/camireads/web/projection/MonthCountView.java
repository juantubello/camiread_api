package net.casapipis.camireads.web.projection;

/** Libros terminados en un mes del anio (1-12), sumando TODOS los anios. */
public interface MonthCountView {
    int getMonth();
    long getAmount();
}
