package com.unimarket.api.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración externalizada del correo (SDD §10): selector de proveedor
 * ({@code log} en dev, {@code resend} en prod), cooldown de reenvío de confirmación
 * (PRD §5, N5) y credenciales solo por entorno.
 */
@ConfigurationProperties("app.notificaciones")
public record PropiedadesNotificaciones(
        String proveedor, Duration cooldownReenvio, String destinoDePrueba, Resend resend) {

    /** Credenciales y remitente de Resend: solo por variables de entorno. */
    public record Resend(String apiKey, String remitente, String baseUrl) {
    }

    /**
     * Dirección que RECIBE todos los correos en local, sea cual sea la cuenta registrada
     * (solo para probar la entrega sin dominio verificado). Prohibida en producción: la
     * Guardia aborta el arranque. Vacía = comportamiento normal.
     */
    public String destinoDePruebaOPorDefecto() {
        return destinoDePrueba == null ? "" : destinoDePrueba.strip();
    }

    /** Cooldown efectivo de reenvío: 2 minutos si no hay valor configurado (PRD §5). */
    public Duration cooldownReenvioOPorDefecto() {
        return cooldownReenvio == null ? Duration.ofMinutes(2) : cooldownReenvio;
    }
}
