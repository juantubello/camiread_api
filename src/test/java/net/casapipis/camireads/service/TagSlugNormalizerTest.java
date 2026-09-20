package net.casapipis.camireads.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests del normalizador de slugs. Es el corazon del modo "tags libres":
 * si esto se rompe, la usuaria termina con tags duplicados
 * ("Tengo en físico" vs "Tengo en fisico").
 *
 * Unit test puro: no levanta Spring ni toca la base.
 */
class TagSlugNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @DisplayName("mayusculas, espacios de sobra y acentos colapsan al mismo slug")
    @CsvSource(delimiter = '|', value = {
            // --- casos pedidos explicitamente ---
            "Romance                | romance",
            "romance                | romance",
            "'  ROMANCE  '          | romance",
            "Tengo en físico        | tengo-en-fisico",
            "Ciencia Ficción        | ciencia-ficcion",
            "Leído en Wattpad       | leido-en-wattpad",

            // --- los 5 tags ya sembrados en la base deben reproducirse exacto ---
            "Favoritos              | favoritos",
            "Sagas por terminar     | sagas-por-terminar",
            "Comprar en físico      | comprar-en-fisico",

            // --- variantes que la usuaria puede llegar a tipear ---
            "TENGO EN FÍSICO        | tengo-en-fisico",
            "'  tengo   en  fisico '| tengo-en-fisico",
            "Tengo En Fisico        | tengo-en-fisico",
            "leído en wattpad       | leido-en-wattpad",

            // --- acentos varios ---
            "Época dorada           | epoca-dorada",
            "Ángeles y demonios     | angeles-y-demonios",
            "Corazón                | corazon",
            "Sofía Úrsula           | sofia-ursula",

            // --- puntuacion y separadores raros ---
            "Sci-Fi                 | sci-fi",
            "'Terror / Gore'        | terror-gore",
            "'¡Relectura!'          | relectura",
            "'Libros 2024'          | libros-2024",
            "'--Guiones--'          | guiones",
            "'New_Adult'            | new-adult",
    })
    void normalizaComoCorresponde(String input, String expected) {
        assertEquals(expected, TagSlugNormalizer.toSlug(input));
    }

    @Test
    @DisplayName("'Tengo en físico' matchea el slug ya sembrado en la base")
    void matcheaElTagSembrado() {
        // Este es el caso que evita crear un duplicado del tag id=12 de la base.
        assertEquals("tengo-en-fisico", TagSlugNormalizer.toSlug("Tengo en físico"));
        assertEquals(TagSlugNormalizer.toSlug("Tengo en físico"),
                     TagSlugNormalizer.toSlug("tengo en fisico"));
    }

    @Test
    @DisplayName("null y vacios devuelven string vacio (el service los rechaza con 400)")
    void vacios() {
        assertEquals("", TagSlugNormalizer.toSlug(null));
        assertEquals("", TagSlugNormalizer.toSlug(""));
        assertEquals("", TagSlugNormalizer.toSlug("   "));
        assertEquals("", TagSlugNormalizer.toSlug("!!!"));
    }

    @Test
    @DisplayName("el slug es idempotente: normalizar un slug devuelve el mismo slug")
    void idempotente() {
        for (String s : new String[]{"romance", "tengo-en-fisico", "leido-en-wattpad", "sagas-por-terminar"}) {
            assertEquals(s, TagSlugNormalizer.toSlug(s));
        }
    }

    @Test
    @DisplayName("tags realmente distintos NO colapsan")
    void noColapsaTagsDistintos() {
        assertNotEquals(TagSlugNormalizer.toSlug("Favoritos"),
                        TagSlugNormalizer.toSlug("Favoritas"));
        assertNotEquals(TagSlugNormalizer.toSlug("Tengo en físico"),
                        TagSlugNormalizer.toSlug("Comprar en físico"));
    }
}
