package com.unimarket.api.notifications;

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

    @Override
    public void enviar(MensajeCorreo mensaje) {
        RegistroCorreoDev.registrar(mensaje);
    }
}
