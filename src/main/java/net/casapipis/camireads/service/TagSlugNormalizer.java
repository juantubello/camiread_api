package net.casapipis.camireads.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalizador de slugs de tags. Es el corazon del modo "tags libres":
 * si dos nombres normalizan al mismo slug, son EL MISMO tag.
 *
 * Reglas (en este orden):
 *   1. trim
 *   2. Unicode NFD + borrar marcas diacriticas  ("fisico" == "fisico" con tilde)
 *   3. minusculas (Locale.ROOT, para no caer en el bug del turco con la "I")
 *   4. todo lo que no sea [a-z0-9] pasa a guion (espacios, puntuacion, guiones bajos)
 *   5. colapsar guiones repetidos
 *   6. sacar guiones del principio y del final
 *
 * Esto reproduce exactamente los slugs ya sembrados en la base
 * ("Tengo en fisico" -> tengo-en-fisico), que es lo que evita que se creen
 * tags duplicados cuando la usuaria escribe el nombre con acentos.
 *
 * Nota: la descomposicion NFD convierte la "n con virgulilla" en "n", asi que
 * "Anio" y "Ano" colapsan al mismo slug. Es consistente con el resto de la
 * regla de acentos y esta asumido.
 */
public final class TagSlugNormalizer {

    private TagSlugNormalizer() {
        // utilidad estatica
    }

    public static String toSlug(String raw) {
        if (raw == null) {
            return "";
        }

        String slug = raw.trim();
        if (slug.isEmpty()) {
            return "";
        }

        // NFD separa la letra base de su marca diacritica; despues borramos las marcas.
        slug = Normalizer.normalize(slug, Normalizer.Form.NFD);
        slug = slug.replaceAll("\\p{M}+", "");

        slug = slug.toLowerCase(Locale.ROOT);

        // Cualquier cosa que no sea alfanumerica ASCII se vuelve separador.
        slug = slug.replaceAll("[^a-z0-9]+", "-");

        // Guiones repetidos -> uno solo; y afuera los de las puntas.
        slug = slug.replaceAll("-{2,}", "-");
        slug = slug.replaceAll("^-+", "");
        slug = slug.replaceAll("-+$", "");

        return slug;
    }
}
