package com.unimarket.api.moderation;

import com.unimarket.api.catalog.Decision;
import com.unimarket.api.catalog.DtosAnuncios;
import com.unimarket.api.catalog.EstadoAnuncio;
import com.unimarket.api.catalog.EstadoRevision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos del wire de moderation (openapi.yaml, tag Moderacion): enum inválido en
 * {@code decision} no deserializa → 400 estructural; motivo vacío en rechazo/retracción
 * → 422 (regla de negocio, R5).
 */
public final class DtosModeracion {

    private DtosModeracion() {
    }

    /** Decisión explícita de revisión; el motivo solo aplica a rechazar y retraer (R5). */
    public record DecisionRequest(
            @NotNull Decision decision,
            String motivoRechazo) {
    }

    /** Denuncia de un anuncio (R19): el detalle es obligatorio cuando el motivo es {@code otro}. */
    public record DenunciaRequest(
            @NotNull MotivoDenuncia motivo,
            @Size(max = 500) String detalle) {
    }

    /**
     * Ítem de la cola de denuncias. NUNCA incluye al denunciante: su identidad no sale de
     * la base de datos ni para el vendedor ni en la cola (privacidad, R19).
     */
    public record DenunciaPanel(
            UUID id,
            UUID anuncioId,
            MotivoDenuncia motivo,
            String detalle,
            EstadoDenuncia estado,
            Instant fechaCreacion,
            UUID resueltoPor,
            Instant fechaResolucion,
            String motivoResolucion) {

        public static DenunciaPanel de(Denuncia denuncia) {
            return new DenunciaPanel(
                    denuncia.getId(),
                    denuncia.getAnuncioId(),
                    denuncia.getMotivo(),
                    denuncia.getDetalle(),
                    denuncia.getEstado(),
                    denuncia.getFechaCreacion(),
                    denuncia.getResueltoPor(),
                    denuncia.getFechaResolucion(),
                    denuncia.getMotivoResolucion());
        }
    }

    /** Resolución de una denuncia: revisada (se actuó) o descartada (no procedía). */
    public record ResolucionRequest(@NotNull EstadoDenuncia estado, String motivo) {
    }

    /**
     * Anuncio en la cola de moderación con el contador de denuncias abiertas (R19).
     *
     * <p>Es un DTO propio de {@code moderation} y no {@code AnuncioPanel} con un campo más:
     * añadir el contador al DTO de {@code catalog} obligaría a catalog a leer denuncias, y
     * como moderation ya depende de catalog eso sería un ciclo de módulos (ArchUnit). Se
     * copian los campos para conservar EXACTAMENTE la forma plana del wire.
     */
    public record PanelModeracion(
            UUID id,
            String nombre,
            BigDecimal precio,
            String descripcion,
            Integer categoriaId,
            List<String> imagenUrls,
            UUID vendedorId,
            String vendedorNombre,
            Instant fechaCreacion,
            EstadoAnuncio estado,
            Instant fechaActualizacion,
            EstadoRevision estadoRevision,
            String motivoRechazo,
            boolean retraido,
            Instant fechaRevision,
            long denunciasAbiertas) {

        public static PanelModeracion de(DtosAnuncios.AnuncioPanel panel, long denunciasAbiertas) {
            return new PanelModeracion(
                    panel.id(),
                    panel.nombre(),
                    panel.precio(),
                    panel.descripcion(),
                    panel.categoriaId(),
                    panel.imagenUrls(),
                    panel.vendedorId(),
                    panel.vendedorNombre(),
                    panel.fechaCreacion(),
                    panel.estado(),
                    panel.fechaActualizacion(),
                    panel.estadoRevision(),
                    panel.motivoRechazo(),
                    panel.retraido(),
                    panel.fechaRevision(),
                    denunciasAbiertas);
        }
    }
}
