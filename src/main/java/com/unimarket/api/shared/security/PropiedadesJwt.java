package com.unimarket.api.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración del kernel JWT (SDD §8, D2): secreto HS256 externalizado y TTL del
 * access token. El secreto nunca vive en el repositorio, se inyecta por entorno.
 *
 * @param secreto   clave de firma HS256; obligatoria en producción
 * @param ttlAcceso vigencia del access token (15 min por defecto)
 */
@ConfigurationProperties("app.seguridad.jwt")
public record PropiedadesJwt(String secreto, Duration ttlAcceso) {
}
