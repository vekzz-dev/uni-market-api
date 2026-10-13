package com.unimarket.api.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Guarda de arranque del perfil {@code prod} (SDD §8/§10): verifica ANTES de servir
 * tráfico que las credenciales críticas existen, y aborta con un mensaje accionable si
 * falta alguna. Un entorno de producción nunca debe arrancar "degradado en silencio"
 * (JWT sin secreto, R2 sin claves, Resend sin API key).
 *
 * <p>Se implementa con {@code @Value} de cadenas y no con las clases de propiedades de
 * otros módulos para respetar la frontera de módulos (shared no depende de ellos).
 */
@Component
@Profile("prod")
public class GuardiaProduccion {

    public GuardiaProduccion(
            PropiedadesJwt jwt,
            @Value("${app.media.proveedor:}") String proveedorMedia,
            @Value("${app.media.r2.endpoint:}") String r2Endpoint,
            @Value("${app.media.r2.bucket:}") String r2Bucket,
            @Value("${app.media.r2.access-key-id:}") String r2AccessKey,
            @Value("${app.media.r2.secret-access-key:}") String r2Secret,
            @Value("${app.media.r2.url-publica:}") String r2UrlPublica,
            @Value("${app.notificaciones.proveedor:}") String proveedorCorreo,
            @Value("${app.notificaciones.resend.api-key:}") String resendApiKey,
            @Value("${app.notificaciones.resend.remitente:}") String resendRemitente) {
        exigir(jwt != null && noVacio(jwt.secreto()), "JWT_SECRETO (firma de tokens)");
        if ("r2".equals(proveedorMedia)) {
            exigir(noVacio(r2Endpoint), "MEDIA_R2_ENDPOINT (storage R2)");
            exigir(noVacio(r2Bucket), "MEDIA_R2_BUCKET (storage R2)");
            exigir(noVacio(r2AccessKey), "MEDIA_R2_ACCESS_KEY_ID (storage R2)");
            exigir(noVacio(r2Secret), "MEDIA_R2_SECRET_ACCESS_KEY (storage R2)");
            exigir(noVacio(r2UrlPublica), "MEDIA_R2_URL_PUBLICA (storage R2)");
        }
        if ("resend".equals(proveedorCorreo)) {
            exigir(noVacio(resendApiKey), "RESEND_API_KEY (email)");
            exigir(noVacio(resendRemitente), "RESEND_REMITENTE (email)");
        }
    }

    private static void exigir(boolean condicion, String variableFaltante) {
        if (!condicion) {
            throw new IllegalStateException(
                    "Configuración de producción incompleta: falta " + variableFaltante
                            + ". Define las variables de entorno requeridas y reintenta.");
        }
    }

    private static boolean noVacio(String valor) {
        return valor != null && !valor.isBlank();
    }
}
