package com.unimarket.api.catalog;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos del wire de catalog (openapi.yaml, tag Catálogo/Anuncios): {@code camelCase};
 * la respuesta NUNCA incluye las claves de storage ({@code imagenesPaths}, R8).
 */
public final class DtosAnuncios {

    private DtosAnuncios() {
    }

    /**
     * Resumen público del catálogo (openapi: {@code AnuncioResumen}): solo {@code imagenUrls}
     * viaja al wire (R8). NO incluye {@code estado} ni {@code fechaActualizacion}: el listado
     * solo expone anuncios visibles, así que el estado sería una constante, y el ciclo de vida
     * pertenece al detalle. Publicar campos de más hace que el cliente generado los descarte
     * en silencio.
     */
    public record AnuncioResumen(
            UUID id,
            String nombre,
            BigDecimal precio,
            String descripcion,
            Integer categoriaId,
            List<String> imagenUrls,
            UUID vendedorId,
            Instant fechaCreacion) {

        public static AnuncioResumen de(Anuncio anuncio) {
            return new AnuncioResumen(
                    anuncio.getId(),
                    anuncio.getNombre(),
                    anuncio.getPrecio(),
                    anuncio.getDescripcion(),
                    anuncio.getCategoriaId(),
                    anuncio.getImagenesUrls(),
                    anuncio.getVendedorId(),
                    anuncio.getFechaCreacion());
        }
    }

    /**
     * Detalle público (openapi: {@code AnuncioDetalle} = {@code AnuncioResumen} + {@code estado}
     * + {@code fechaActualizacion}). Es también la vista que consume mensajería: en una
     * conversación importa si el anuncio ya se vendió, se ocultó o se dio de baja (R16).
     */
    public record AnuncioDetalle(
            UUID id,
            String nombre,
            BigDecimal precio,
            String descripcion,
            Integer categoriaId,
            List<String> imagenUrls,
            UUID vendedorId,
            Instant fechaCreacion,
            EstadoAnuncio estado,
            Instant fechaActualizacion) {

        public static AnuncioDetalle de(Anuncio anuncio) {
            return new AnuncioDetalle(
                    anuncio.getId(),
                    anuncio.getNombre(),
                    anuncio.getPrecio(),
                    anuncio.getDescripcion(),
                    anuncio.getCategoriaId(),
                    anuncio.getImagenesUrls(),
                    anuncio.getVendedorId(),
                    anuncio.getFechaCreacion(),
                    anuncio.getEstado(),
                    anuncio.getFechaActualizacion());
        }
    }

    /** Vista del vendedor/panel: añade el estado de revisión y su trail (R5/R6). */
    public record AnuncioPanel(
            UUID id,
            String nombre,
            BigDecimal precio,
            String descripcion,
            Integer categoriaId,
            List<String> imagenUrls,
            UUID vendedorId,
            Instant fechaCreacion,
            EstadoAnuncio estado,
            Instant fechaActualizacion,
            EstadoRevision estadoRevision,
            String motivoRechazo,
            Instant fechaRevision) {

        public static AnuncioPanel de(Anuncio anuncio) {
            return new AnuncioPanel(
                    anuncio.getId(),
                    anuncio.getNombre(),
                    anuncio.getPrecio(),
                    anuncio.getDescripcion(),
                    anuncio.getCategoriaId(),
                    anuncio.getImagenesUrls(),
                    anuncio.getVendedorId(),
                    anuncio.getFechaCreacion(),
                    anuncio.getEstado(),
                    anuncio.getFechaActualizacion(),
                    anuncio.getEstadoRevision(),
                    anuncio.getMotivoRechazo(),
                    anuncio.getFechaRevision());
        }
    }

    /** Edición de contenido (PATCH): solo los campos presentes se actualizan (R6). */
    public record ActualizarRequest(
            @Size(max = 255) String nombre,
            BigDecimal precio,
            String descripcion,
            Integer categoriaId) {
    }

    /** Toggle de visibilidad: {@code true} → disponible, {@code false} → inactivo. */
    public record VisibilidadRequest(@NotNull Boolean visible) {
    }

    /** Categoría de lectura pública (semilla V2 del DBDD §4). */
    public record CategoriaRespuesta(Integer id, String nombre, String descripcion, String icono, Integer orden) {
    }

    /** Colección simple de categorías: {@code datos} sin paginación (openapi). */
    public record CategoriasRespuesta(List<CategoriaRespuesta> datos) {
    }
}
