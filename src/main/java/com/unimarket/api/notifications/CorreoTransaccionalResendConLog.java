package com.unimarket.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Modo local completo (D12): envía el correo REAL por Resend y además deja el mensaje con
 * el token en el log. Resuelve el caso de desarrollo en el que quieres comprobar que el
 * proveedor entrega de verdad, pero no quieres depender del buzón para confirmar la cuenta.
 *
 * <p>Se activa con {@code app.notificaciones.proveedor=resend+log}. El log se escribe
 * <strong>antes</strong> del envío: si Resend falla, el token sigue disponible y el oyente
 * reintenta con backoff sin dejar al usuario sin forma de confirmar. Prohibido en
 * producción (la {@code GuardiaProduccion} aborta el arranque): un token crudo en los logs
 * de producción es una credencial filtrada.
 */
@Service
@ConditionalOnProperty(name = "app.notificaciones.proveedor", havingValue = "resend+log")
public class CorreoTransaccionalResendConLog implements CorreoTransaccional {

    private final CorreoTransaccionalResend resend;

    public CorreoTransaccionalResendConLog(PropiedadesNotificaciones propiedades) {
        this.resend = new CorreoTransaccionalResend(propiedades);
    }

    @Override
    public void enviar(MensajeCorreo mensaje) {
        RegistroCorreoDev.registrar(mensaje);
        resend.enviar(mensaje);
    }
}
