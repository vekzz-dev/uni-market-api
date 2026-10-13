package com.unimarket.api.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuración externalizada de medios (SDD §10, R8): ÚNICA fuente de los límites de
 * subida (avatar 2 MB; imágenes de anuncio 5 MB x hasta 5, R8) y selector de adaptador
 * ({@code filesystem} en dev — default — o {@code r2} en prod, D14/N8).
 */
@ConfigurationProperties("app.media")
public record PropiedadesMedia(
        String raiz,
        String proveedor,
        long avatarMaxBytes,
        List<String> tiposAvatar,
        int anuncioMaxImagenes,
        long imagenMaxBytes,
        List<String> tiposImagen,
        R2 r2) {

    /** Credenciales y URL pública de Cloudflare R2: solo por variables de entorno (N8). */
    public record R2(String endpoint, String bucket, String accessKeyId, String secretAccessKey, String urlPublica) {
    }
}
