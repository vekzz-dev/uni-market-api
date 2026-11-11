package com.unimarket.api.notifications;

/**
 * Puerto de correo transaccional (D12, PRD §5): solo confirmación de registro y
 * restablecimiento. El log de entrega son logs estructurados de aplicación, sin tabla de
 * auditoría (SDD N2 resuelta).
 */
public interface CorreoTransaccional {

    /**
     * Mensaje transaccional: {@code texto} es la versión plana (la que se imprime en el log
     * de dev y la que viaja como alternativa) y {@code html} la versión con formato. El PIN
     * de un solo uso va en ambas.
     */
    record MensajeCorreo(String destino, String asunto, String texto, String html) {
    }

    /** Envía el mensaje; el fallo lo gestiona el oyente con backoff, nunca el HTTP. */
    void enviar(MensajeCorreo mensaje);
}
