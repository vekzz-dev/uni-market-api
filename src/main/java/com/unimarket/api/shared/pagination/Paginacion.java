package com.unimarket.api.shared.pagination;

/**
 * Modos de paginación del envelope de colecciones (SDD §6): cursor opaco compuesto para
 * catálogo e historial de chat, y offset con total para los paneles. Sellada a propósito:
 * solo existen estos dos modos en el contrato.
 */
public sealed interface Paginacion permits PaginacionCursor, PaginacionOffset {
}
