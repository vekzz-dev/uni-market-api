package com.unimarket.api.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración externalizada del correo (SDD §10): selector de proveedor
 * ({@code log} en dev, {@code resend} en prod), cooldown de reenvío de confirmación
 * (PRD §5, N5) y credenciales solo por entorno.
 */
@ConfigurationProperties("app.notificaciones")
public record PropiedadesNotificaciones(String proveedor, Duration cooldownReenvio, Resend resend) {

    /** Credenciales y remitente de Resend: solo por variables de entorno. */
    public record Resend(String apiKey, String remitente, String baseUrl) {
    }

    /** Cooldown efectivo de reenvío: 2 minutos si no hay valor configurado (PRD §5). */
    public Duration cooldownReenvioOPorDefecto() {
        return cooldownReenvio == null ? Duration.ofMinutes(2) : cooldownReenvio;
    }
}
