package com.unimarket.api.identity;

import java.util.Locale;

/**
 * Regla del nombre público del vendedor (R17): se publica el nombre de pila y la inicial
 * del ÚLTIMO apellido ({@code "Ana Pérez"} → {@code "Ana P."}), nunca el nombre completo.
 *
 * <p>El cálculo vive aquí, junto al dato que lo alimenta ({@code usuarios.nombre}), y no en
 * el módulo que lo consume: `catalog` solo ve el servicio de aplicación expuesto (SDD §4).
 * Sin nombre utilizable se responde {@link #SIN_NOMBRE}, un genérico que no revela si la
 * cuenta existe, está dada de baja o simplemente no tiene nombre (R1/R15).
 */
public final class NombresPublicos {

    /** Texto publicado cuando no hay nombre resoluble; no filtra el estado de la cuenta. */
    public static final String SIN_NOMBRE = "Vendedor";

    /** Techo del wire: {@code vendedorNombre} declara {@code maxLength: 60} en el contrato. */
    static final int MAXIMO = 60;

    private NombresPublicos() {
    }

    /**
     * Nombre público a partir del nombre completo del usuario.
     *
     * @param nombreCompleto el nombre tal como lo registró el usuario (puede ser nulo o estar en blanco)
     * @return nombre de pila + inicial del último apellido, o {@link #SIN_NOMBRE}
     */
    public static String publico(String nombreCompleto) {
        if (nombreCompleto == null) {
            return SIN_NOMBRE;
        }
        String limpio = nombreCompleto.strip();
        if (limpio.isEmpty()) {
            return SIN_NOMBRE;
        }
        String[] partes = limpio.split("\\s+");
        String publico = partes.length == 1
                ? partes[0]
                : partes[0] + " " + inicial(partes[partes.length - 1]);
        return recortar(publico);
    }

    /** Solo la inicial del apellido, en mayúscula: el apellido completo nunca viaja al wire. */
    private static String inicial(String apellido) {
        return apellido.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }

    /** El contrato es la garantía del wire: lo que excede 60 caracteres se recorta. */
    private static String recortar(String valor) {
        return valor.length() <= MAXIMO ? valor : valor.substring(0, MAXIMO);
    }
}
