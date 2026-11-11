package com.unimarket.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registro del correo crudo en el log de la aplicación, en una sola definición.
 *
 * <p>Lo usan dos caminos: el proveedor {@code log} (dev sin proveedor externo) y el modo
 * {@code resend+log}, que envía de verdad por Resend y además deja el token en el log para
 * completar la confirmación sin abrir el buzón. Si el formato viviera duplicado, los dos
 * caminos se desincronizarían.
 */
final class RegistroCorreoDev {

    private static final Logger log = LoggerFactory.getLogger(RegistroCorreoDev.class);

    private RegistroCorreoDev() {
    }

    static void registrar(CorreoTransaccional.MensajeCorreo mensaje) {
        log.info("Correo transaccional (dev): destino={} asunto={} texto={} html={}",
                mensaje.destino(), mensaje.asunto(), mensaje.texto(),
                mensaje.html() == null || mensaje.html().isBlank() ? "no" : "sí");
    }
}
