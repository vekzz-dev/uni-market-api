package com.unimarket.api.moderation;

import com.unimarket.api.catalog.Decision;
import jakarta.validation.constraints.NotNull;

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
}
