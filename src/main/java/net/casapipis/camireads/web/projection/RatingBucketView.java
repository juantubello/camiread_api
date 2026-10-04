package net.casapipis.camireads.web.projection;

/** Un escalon del histograma de puntajes: cuantas resenias tienen ese rating (0-5). */
public interface RatingBucketView {
    int getRating();
    long getAmount();
}
