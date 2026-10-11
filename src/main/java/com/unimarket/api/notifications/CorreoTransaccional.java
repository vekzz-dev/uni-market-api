package com.unimarket.api.notifications;

/**
 * Puerto de correo transaccional (D12, PRD §5): solo confirmación de registro y
 * restablecimiento. El log de entrega son logs estructurados de aplicación, sin tabla de
 * auditoría (SDD N2 resuelta).
 */
public interface CorreoTransaccional {

    /** Mensaje mínimo: destino, asunto y texto con el token crudo de un solo uso. */
    record MensajeCorreo(String destino, String asunto, String texto) {
    }

    /** Envía el mensaje; el fallo lo gestiona el oyente con backoff, nunca el HTTP. */
    void enviar(MensajeCorreo mensaje);
}
