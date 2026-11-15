package com.unimarket.api.moderation;

/**
 * Ciclo de vida de una denuncia (R19): nace {@code abierta} y solo un moderador la cierra
 * como {@code revisada} (se actuó) o {@code descartada} (no procedía).
 */
public enum EstadoDenuncia {
    abierta,
    revisada,
    descartada
}
