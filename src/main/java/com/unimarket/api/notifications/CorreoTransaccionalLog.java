package com.unimarket.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Adaptador de log a consola para dev (D12): imprime el mensaje con el token crudo para
 * completar confirmación/reset sin proveedor externo. Proveedor por defecto
 * ({@code app.notificaciones.proveedor=log}).
 */
@Service
@ConditionalOnProperty(name = "app.notificaciones.proveedor", havingValue = "log", matchIfMissing = true)
public class CorreoTransaccionalLog implements CorreoTransaccional {

    private static final Logger log = LoggerFactory.getLogger(CorreoTransaccionalLog.class);

    @Override
    public void enviar(MensajeCorreo mensaje) {
        log.info("Correo transaccional (dev): destino={} asunto={} texto={}",
                mensaje.destino(), mensaje.asunto(), mensaje.texto());
    }
}
