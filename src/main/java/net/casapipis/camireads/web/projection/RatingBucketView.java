package net.casapipis.camireads.web.projection;

import java.math.BigDecimal;

/**
 * Un escalon del histograma de puntajes (0-5): cuantas resenias caen en ese
 * balde y la suma de sus puntajes reales. Desde la Fase 9 el balde es el piso
 * del puntaje (3.75 -> 3, 0.5 -> 1; 0 es solo "sin calificar").
 */
public interface RatingBucketView {
    int getRating();
    long getAmount();
    BigDecimal getRatingSum();
}
