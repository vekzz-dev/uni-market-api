package com.unimarket.api.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuración externalizada de medios (SDD §10, R8): ÚNICA fuente de los límites de
 * subida. Raíz del filesystem en dev vía {@code MEDIA_DIR} (D14, datos de usuario bajo
 * el home, OS-independiente).
 *
 * <p>TODO(prod): adaptador S3-compatible apuntando a Cloudflare R2 tras
 * {@code app.media.proveedor=r2} con endpoint/bucket/keys por entorno + URL pública
 * {@code r2.dev} o dominio propio (D14). El cambio es un bean tras este puerto.
 */
@ConfigurationProperties("app.media")
public record PropiedadesMedia(String raiz, long avatarMaxBytes, List<String> tiposAvatar) {
}
