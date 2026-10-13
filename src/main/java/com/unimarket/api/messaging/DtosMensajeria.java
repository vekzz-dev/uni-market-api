package com.unimarket.api.messaging;

import com.unimarket.api.catalog.DtosAnuncios;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Contratos del wire de messaging (openapi.yaml, tag Conversaciones/Mensajes):
 * {@code camelCase}; el texto del mensaje se valida en el servicio (422 de regla, R10),
 * no con Bean Validation (estructural → 400).
 */
public final class DtosMensajeria {

    private DtosMensajeria() {
    }

    /** Apertura: único campo aceptado; el {@code vendedorId} del cliente se ignora (R9). */
    public record CrearConversacionRequest(@NotNull UUID anuncioId) {
    }

    /** Envío: recortado y no vacío (422, R10); la respuesta HTTP es el contrato (sin tiempo real). */
    public record EnviarMensajeRequest(String texto) {
    }

    public record ConversacionResumen(
            UUID id,
            UUID anuncioId,
            UUID compradorId,
            UUID vendedorId,
            Instant fechaCreacion,
            Instant fechaActualizacion) {

        public static ConversacionResumen de(Conversacion conversacion) {
            return new ConversacionResumen(
                    conversacion.getId(),
                    conversacion.getAnuncioId(),
                    conversacion.getCompradorId(),
                    conversacion.getVendedorId(),
                    conversacion.getFechaCreacion(),
                    conversacion.getFechaActualizacion());
        }
    }

    /** Ítem del panel "mis conversaciones" (openapi: resumen + anuncio + ultimoMensaje + noLeidos). */
    public record ConversacionItem(
            UUID id,
            UUID anuncioId,
            UUID compradorId,
            UUID vendedorId,
            Instant fechaCreacion,
            Instant fechaActualizacion,
            DtosAnuncios.AnuncioPublico anuncio,
            UltimoMensaje ultimoMensaje,
            long noLeidos) {
    }

    public record UltimoMensaje(UUID id, String texto, UUID remitenteId, Instant fechaCreacion) {

        static UltimoMensaje de(Mensaje mensaje) {
            return new UltimoMensaje(mensaje.getId(), mensaje.getTexto(), mensaje.getRemitenteId(), mensaje.getFechaCreacion());
        }
    }

    /** Detalle de una conversación para participantes (R10). */
    public record ConversacionDetalle(
            UUID id,
            UUID anuncioId,
            UUID compradorId,
            UUID vendedorId,
            Instant fechaCreacion,
            Instant fechaActualizacion,
            DtosAnuncios.AnuncioPublico anuncio,
            long noLeidos) {
    }

    public record MensajeRespuesta(
            UUID id,
            UUID conversacionId,
            UUID remitenteId,
            String texto,
            boolean leido,
            Instant fechaCreacion) {

        public static MensajeRespuesta de(Mensaje mensaje) {
            return new MensajeRespuesta(
                    mensaje.getId(),
                    mensaje.getConversacionId(),
                    mensaje.getRemitenteId(),
                    mensaje.getTexto(),
                    mensaje.isLeido(),
                    mensaje.getFechaCreacion());
        }
    }

    /** Estado del historial tras procesar una página (cursor opaco compuesto, D4). */
    public record HistorialMensajes(java.util.List<MensajeRespuesta> datos, String cursorSiguiente) {
    }
}
