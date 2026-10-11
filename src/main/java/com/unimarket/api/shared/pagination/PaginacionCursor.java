package com.unimarket.api.shared.pagination;

/**
 * Paginación por cursor (D4, contrato {@code PaginacionCursor}): {@code cursorSiguiente}
 * es opaco para el cliente y {@code null} en la última página.
 */
public record PaginacionCursor(String cursorSiguiente) implements Paginacion {
}
