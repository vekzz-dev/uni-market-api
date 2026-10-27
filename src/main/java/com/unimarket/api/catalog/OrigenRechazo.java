package com.unimarket.api.catalog;

/**
 * Procedencia del rechazo (R5, issue #4): un anuncio puede estar {@code rechazado} porque
 * la revisión lo rechazó ({@code rechazo}) o porque una aprobación previa se retiró
 * ({@code retraccion}). El estado publicado sigue siendo {@code rechazado}; este dato es
 * la dimensión que separa el chip «Rechazados» del chip «Retraídos» sin ensuciar el enum
 * {@link EstadoRevision} (que es un contrato ya consumido por los clientes).
 */
public enum OrigenRechazo {
    rechazo,
    retraccion
}
