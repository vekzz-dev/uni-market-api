package com.unimarket.api.shared.texto;

import java.util.Locale;

/**
 * Patrón {@code LIKE} de las búsquedas de texto libre (O3). Vive en {@code shared} porque lo
 * usan varios módulos (catálogo y panel admin) y duplicar la regla de escape terminaría en dos
 * comportamientos distintos: el usuario busca literal, así que {@code %}, {@code _} y {@code \}
 * nunca actúan como comodines.
 *
 * <p>El término se normaliza a minúsculas y el llamador compara contra la columna en minúsculas
 * ({@code lower(columna)}), de modo que la búsqueda es case-insensitive con un índice funcional.
 */
public final class PatronesLike {

    /** Carácter de escape declarado en el {@code LIKE} de cada consulta. */
    public static final char ESCAPE = '\\';

    private PatronesLike() {
    }

    /** Patrón "contiene" del término: {@code %término%} con el texto normalizado y escapado. */
    public static String contiene(String termino) {
        return "%" + escapar(termino.strip().toLowerCase(Locale.ROOT)) + "%";
    }

    /** Escapa {@code %}, {@code _} y {@code \} para que el usuario busque caracteres literales. */
    public static String escapar(String texto) {
        return texto
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
