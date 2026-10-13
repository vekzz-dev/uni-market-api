package com.unimarket.api.messaging;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * Predicados de mensajería: participación (R10), conversación del historial y el cursor
 * compuesto {@code (fechaCreacion, id)} hacia atrás (D4).
 */
public final class EspecificacionesMensajeria {

    private EspecificacionesMensajeria() {
    }

    /** "Mis conversaciones": participante como comprador O vendedor (UNION con los índices). */
    public static Specification<Conversacion> deParticipante(UUID usuarioId) {
        return (raiz, consulta, cb) -> cb.or(
                cb.equal(raiz.get("compradorId"), usuarioId),
                cb.equal(raiz.get("vendedorId"), usuarioId));
    }

    public static Specification<Mensaje> deConversacion(UUID conversacionId) {
        return (raiz, consulta, cb) -> cb.equal(raiz.get("conversacionId"), conversacionId);
    }

    /** Historial hacia atrás: tuplas estrictamente anteriores al cursor (página inicial = recientes). */
    public static Specification<Mensaje> antesDelCursor(Instant fechaCreacion, UUID id) {
        return (raiz, consulta, cb) -> cb.or(
                cb.lessThan(raiz.get("fechaCreacion"), fechaCreacion),
                cb.and(
                        cb.equal(raiz.get("fechaCreacion"), fechaCreacion),
                        cb.lessThan(raiz.get("id"), id)));
    }
}
