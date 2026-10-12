package com.unimarket.api.catalog;

/**
 * Disponibilidad comercial del anuncio (DBDD §3, CHECK {@code anuncios_estado_check}):
 * {@code disponible}/{@code inactivo}/{@code vendido}/{@code eliminado}; este último es la
 * baja lógica (R1: nunca hay borrado físico del anuncio).
 */
public enum EstadoAnuncio {
    disponible,
    inactivo,
    vendido,
    eliminado
}
