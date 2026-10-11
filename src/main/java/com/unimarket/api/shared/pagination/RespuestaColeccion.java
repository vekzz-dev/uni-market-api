package com.unimarket.api.shared.pagination;

import java.util.List;

/**
 * Envelope único de colecciones: {@code datos} + {@code paginacion} (SDD §6). La
 * paginación es uno de los dos modos de {@link Paginacion}.
 *
 * @param datos      recursos de la página
 * @param paginacion cursor o offset con total, según el endpoint
 * @param <T>        tipo del recurso de la colección
 */
public record RespuestaColeccion<T>(List<T> datos, Paginacion paginacion) {
}
