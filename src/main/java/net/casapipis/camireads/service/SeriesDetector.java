package net.casapipis.camireads.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saca la saga del titulo estilo Goodreads: "Libro (Saga, #N)" (Fase 10b).
 *
 * La regex es LA MISMA que usa el front para mostrar la saga sugerida: si
 * cambia aca, cambia alla (y al reves). Acepta:
 *   - "Devilish King (Valentino Empire, #1)"          -> Valentino Empire, 1
 *   - "Dark Deception (Deception Trilogy #0.5)"       -> sin coma tambien vale
 *   - "Novella (Saga, #2.5)"                          -> tomos decimales
 *   - espacios despues del parentesis final
 * y NO acepta "Imperio en llamas (Fénix & Dragón 2)": sin "#" no hay forma
 * de distinguir un numero de tomo de un numero que es parte del nombre.
 *
 * La clave de agrupacion es el slug del nombre (TagSlugNormalizer, el mismo
 * de sagas.slug): "Hayes Brothers" y "hayes brothers" son la misma saga.
 * A proposito NO se agrupa por autor: en la base el mismo autor aparece
 * escrito distinto ("L. J. Shen" / "L.J. Shen") y partiria la saga en dos.
 */
public final class SeriesDetector {

    private static final Pattern SERIES = Pattern.compile("\\(([^()]+?),?\\s*#(\\d+(?:\\.\\d+)?)\\)\\s*$");

    /** sagas.name, sagas.slug y saga_series_keys.series_key son VARCHAR(120). */
    static final int MAX_LEN = 120;

    private SeriesDetector() {
        // utilidad estatica
    }

    /**
     * @param name   nombre de la saga tal como esta en el titulo (trim, espacios colapsados)
     * @param key    slug del nombre: clave de saga_series_keys
     * @param volume numero de tomo (puede ser 0.5, 2.5...)
     */
    public record Series(String name, String key, BigDecimal volume) {
    }

    /** La saga del titulo, o null si el titulo no trae "(Saga, #N)" al final. */
    public static Series detect(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = SERIES.matcher(title);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1).trim().replaceAll("\\s{2,}", " ");
        String key = TagSlugNormalizer.toSlug(name);
        // Un nombre hecho solo de simbolos no da clave; y uno larguisimo no
        // entra en las columnas: en los dos casos, "no trae saga".
        if (name.isEmpty() || key.isEmpty() || name.length() > MAX_LEN || key.length() > MAX_LEN) {
            return null;
        }
        return new Series(name, key, new BigDecimal(m.group(2)));
    }

    /**
     * Orden "natural" de titulos para desempatar tomos iguales: los numeros se
     * comparan como numeros ("Libro 2" antes que "Libro 10") y el resto sin
     * distinguir mayusculas.
     */
    public static final Comparator<String> NATURAL = SeriesDetector::compareNatural;

    static int compareNatural(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i;
                int sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                int c = new BigInteger(x.substring(si, i)).compareTo(new BigInteger(y.substring(sj, j)));
                if (c != 0) {
                    return c;
                }
            } else {
                if (cx != cy) {
                    return Character.compare(cx, cy);
                }
                i++;
                j++;
            }
        }
        int rest = Integer.compare(x.length() - i, y.length() - j);
        return rest != 0 ? rest : a.compareTo(b);
    }
}
