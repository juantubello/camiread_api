package net.casapipis.camireads.web.projection;

/**
 * Paginas leidas, calculadas sobre goodreads_import (no sobre books).
 *
 * booksMarkedRead    = filas con exclusive_shelf = 'read'
 * booksWithPageCount = de esas, las que ademas traen number_of_pages
 * totalPages         = la suma de esas paginas
 */
public interface PagesView {
    long getBooksMarkedRead();
    long getBooksWithPageCount();
    long getTotalPages();
}
