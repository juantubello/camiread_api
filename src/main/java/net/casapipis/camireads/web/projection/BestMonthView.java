package net.casapipis.camireads.web.projection;

/** El mes concreto (anio + mes) con mas libros terminados de toda la historia. */
public interface BestMonthView {
    int getYear();
    int getMonth();
    long getAmount();
}
