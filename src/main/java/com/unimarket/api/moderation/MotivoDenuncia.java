package com.unimarket.api.moderation;

/**
 * Motivos cerrados de una denuncia (R19, issue #9): el CHECK de la BD respalda el dominio.
 * {@code otro} exige {@code detalle} no vacío; el resto puede llevarlo.
 */
public enum MotivoDenuncia {
    prohibido,
    falsificacion,
    estafa,
    spam,
    inadecuado,
    otro
}
