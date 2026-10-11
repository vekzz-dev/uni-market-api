package com.unimarket.api.shared.pagination;

/**
 * Paginación por offset para paneles (contrato {@code PaginacionOffset}): default 20,
 * máximo 100 elementos por página (SDD §6).
 */
public record PaginacionOffset(int pagina, int limite, long total, int totalPaginas) implements Paginacion {
}
