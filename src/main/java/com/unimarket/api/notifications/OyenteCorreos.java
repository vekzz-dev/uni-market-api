package com.unimarket.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envío post-commit asíncrono con backoff simple (D8, PRD §5): el email sale DESPUÉS del
 * commit de la transacción de negocio y un fallo del proveedor jamás revierte el HTTP
 * (los reintentos se agotan en el oyente con logs, sin tabla de auditoría).
 */
@Component
public class OyenteCorreos {

    private static final Logger log = LoggerFactory.getLogger(OyenteCorreos.class);

    /** Intentos de envío antes de descartar (con backoff lineal entre ellos). */
    static final int MAX_INTENTOS = 3;

    private final CorreoTransaccional correos;
    private final PropiedadesNotificaciones propiedades;
    private final PlantillasCorreo plantillas;

    public OyenteCorreos(
            CorreoTransaccional correos, PropiedadesNotificaciones propiedades, PlantillasCorreo plantillas) {
        this.correos = correos;
        this.propiedades = propiedades;
        this.plantillas = plantillas;
    }

    /** Confirmación de registro: el token crudo viaja una sola vez, por email (R11). */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alConfirmar(EventosCorreo.ConfirmacionRequerida evento) {
        String texto = "Hola " + evento.nombre() + ": tu código de confirmación en Uni Market es "
                + evento.pin() + ". Caduca en " + EventosCorreo.VIGENCIA_PIN_TEXTO
                + " y solo se puede usar una vez.";
        enviarConReintentos(new CorreoTransaccional.MensajeCorreo(
                evento.email(),
                "Confirma tu cuenta en Uni Market",
                texto,
                plantillas.confirmacion(evento.nombre(), evento.pin(), EventosCorreo.VIGENCIA_PIN_TEXTO)));
    }

    /** Restablecimiento solicitado en cuenta activa (R13). */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alRestablecer(EventosCorreo.RestablecimientoRequerido evento) {
        String texto = "Hola " + evento.nombre() + ": tu código para restablecer la contraseña de Uni Market es "
                + evento.pin() + ". Caduca en " + EventosCorreo.VIGENCIA_PIN_TEXTO
                + " y solo se puede usar una vez.";
        enviarConReintentos(new CorreoTransaccional.MensajeCorreo(
                evento.email(),
                "Restablece tu contraseña en Uni Market",
                texto,
                plantillas.restablecimiento(evento.nombre(), evento.pin(), EventosCorreo.VIGENCIA_PIN_TEXTO)));
    }

    private void enviarConReintentos(CorreoTransaccional.MensajeCorreo mensaje) {
        CorreoTransaccional.MensajeCorreo enviado = redirigido(mensaje);
        for (int intento = 1; intento <= MAX_INTENTOS; intento++) {
            try {
                correos.enviar(enviado);
                return;
            } catch (Exception e) {
                log.warn("Envío de correo fallido (intento {}/{}): {}",
                        intento, MAX_INTENTOS, e.getMessage());
                if (intento < MAX_INTENTOS) {
                    dormir(1000L * intento);
                }
            }
        }
        log.error("Correo descartado tras {} intentos para {}", MAX_INTENTOS, enviado.destino());
    }

    /**
     * Redirección de pruebas ({@code NOTIFICACIONES_DESTINO_PRUEBA}): TODOS los correos van a
     * una sola bandeja, sea cual sea la cuenta registrada, para verificar la entrega real sin
     * dominio verificado. El destinatario original viaja en el asunto para no perder el rastro.
     * En producción está prohibido (la Guardia aborta) porque desviaría correos de usuarios.
     */
    private CorreoTransaccional.MensajeCorreo redirigido(CorreoTransaccional.MensajeCorreo mensaje) {
        String destino = propiedades.destinoDePruebaOPorDefecto();
        if (destino.isEmpty()) {
            return mensaje;
        }
        return new CorreoTransaccional.MensajeCorreo(
                destino,
                "[para " + mensaje.destino() + "] " + mensaje.asunto(),
                mensaje.texto(),
                mensaje.html());
    }

    private static void dormir(long milis) {
        try {
            Thread.sleep(milis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
