package net.casapipis.camireads.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit (sin base): la saga sale del titulo "Libro (Saga, #N)" igual que en el front. */
class SeriesDetectorTest {

    @Test
    @DisplayName("Formato Goodreads con coma: nombre, clave y tomo")
    void conComa() {
        SeriesDetector.Series s = SeriesDetector.detect("Devilish King (Valentino Empire, #1)");
        assertNotNull(s);
        assertEquals("Valentino Empire", s.name());
        assertEquals("valentino-empire", s.key());
        assertEquals(0, new BigDecimal("1").compareTo(s.volume()));
    }

    @Test
    @DisplayName("Sin coma antes del # tambien vale, y el tomo puede ser 0.5")
    void sinComa() {
        SeriesDetector.Series s = SeriesDetector.detect("Dark Deception (Deception Trilogy #0.5)");
        assertNotNull(s);
        assertEquals("Deception Trilogy", s.name());
        assertEquals("deception-trilogy", s.key());
        assertEquals(0, new BigDecimal("0.5").compareTo(s.volume()));
    }

    @Test
    @DisplayName("Tomo decimal 2.5")
    void decimal() {
        SeriesDetector.Series s = SeriesDetector.detect("Novella (Saga, #2.5)");
        assertNotNull(s);
        assertEquals("Saga", s.name());
        assertEquals(0, new BigDecimal("2.5").compareTo(s.volume()));
    }

    @Test
    @DisplayName("Sin parentesis, sin # o null -> no hay saga")
    void sinSaga() {
        assertNull(SeriesDetector.detect("Sin saga"));
        assertNull(SeriesDetector.detect("Imperio en llamas (Fénix & Dragón 2)"));
        assertNull(SeriesDetector.detect(null));
        assertNull(SeriesDetector.detect("(#3)"));
        assertNull(SeriesDetector.detect("Libro (¿?, #3)"), "un nombre sin letras ni numeros no da clave");
        // El parentesis tiene que estar al FINAL del titulo.
        assertNull(SeriesDetector.detect("Libro (Saga, #1) y algo mas"));
    }

    @Test
    @DisplayName("Espacios al final y dentro del parentesis; acentos y mayusculas no cambian la clave")
    void espacios() {
        SeriesDetector.Series s = SeriesDetector.detect("Twisted Love (Twisted ,  #1)   ");
        assertNotNull(s);
        assertEquals("Twisted", s.name());
        assertEquals(1, s.volume().intValueExact());

        SeriesDetector.Series a = SeriesDetector.detect("Uno (Fénix  Dorado, #1)");
        SeriesDetector.Series b = SeriesDetector.detect("Dos (fenix dorado #2)");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("Fénix Dorado", a.name(), "espacios repetidos se colapsan");
        assertEquals(a.key(), b.key());
    }

    @Test
    @DisplayName("Parentesis anteriores no confunden: toma el ultimo")
    void variosParentesis() {
        SeriesDetector.Series s = SeriesDetector.detect("Libro (edicion especial) (Royal Elite, #2)");
        assertNotNull(s);
        assertEquals("Royal Elite", s.name());
        assertEquals("royal-elite", s.key());
    }

    @Test
    @DisplayName("Orden natural: numeros como numeros, sin distinguir mayusculas")
    void ordenNatural() {
        List<String> l = new ArrayList<>(List.of("libro 10", "Libro 2", "libro 1", "Abc"));
        l.sort(SeriesDetector.NATURAL);
        assertEquals(List.of("Abc", "libro 1", "Libro 2", "libro 10"), l);
        assertTrue(SeriesDetector.NATURAL.compare("a", "A") != 0, "desempata de forma estable");
    }
}
