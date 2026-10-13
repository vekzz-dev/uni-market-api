package com.unimarket.api.catalog;

/**
 * Decisión de revisión del moderador (R5, openapi): el servicio de moderación es el
 * único que la aplica y las columnas de revisión son suyas en exclusiva.
 */
public enum Decision {
    aprobar,
    rechazar,
    retraer
}
