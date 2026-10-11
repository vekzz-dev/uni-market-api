package com.unimarket.api.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración externalizada del correo (SDD §10): selector de proveedor
 * ({@code log} en dev, {@code resend} en prod) y credenciales solo por entorno.
 */
@ConfigurationProperties("app.notificaciones")
public record PropiedadesNotificaciones(String proveedor, Resend resend) {

    /** Credenciales y remitente de Resend: solo por variables de entorno. */
    public record Resend(String apiKey, String remitente) {
    }
}
