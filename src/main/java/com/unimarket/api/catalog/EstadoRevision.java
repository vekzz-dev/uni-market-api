package com.unimarket.api.catalog;

/**
 * Estado de moderación del anuncio (DBDD §3, CHECK {@code anuncios_estado_revision_check}):
 * todo anuncio nace {@code pendiente} sin excepción de rol (R5) y solo el flujo de
 * moderación escribe este campo junto con su trail (R6).
 */
public enum EstadoRevision {
    pendiente,
    aprobado,
    rechazado
}
