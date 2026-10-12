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

    /** Vista pública (resumen/detalle): solo {@code imagenUrls} viaja al wire (R8). */
    public record AnuncioPublico(
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

        public static AnuncioPublico de(Anuncio anuncio) {
            return new AnuncioPublico(
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
