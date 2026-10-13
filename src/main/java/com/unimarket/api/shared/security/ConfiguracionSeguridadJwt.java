package com.unimarket.api.shared.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Kernel JWT del SDD §8: decoder HS256 (D2) y registro del filtro de autoridad por
 * request (D3). El filtro se registra aquí —y no como {@code @Component}— porque su
 * condición de existencia depende del puerto {@link EstadoUsuarioProvider} que aportará
 * {@code identity}; hasta entonces la app arranca sin él.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PropiedadesJwt.class, PropiedadesRateLimit.class})
public class ConfiguracionSeguridadJwt {

    /** Decoder HS256 con el secreto externalizado; sin secreto no hay bean (T4). */
    @Bean
    @Conditional(SecretoConfigurado.class)
    JwtDecoder jwtDecoder(PropiedadesJwt propiedades) {
        SecretKey clave = new SecretKeySpec(propiedades.secreto().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(clave).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** Filtro condicional: solo existe cuando {@code identity} publica su implementación. */
    @Bean
    @ConditionalOnBean(EstadoUsuarioProvider.class)
    FiltroAutoridadUsuario filtroAutoridadUsuario(EstadoUsuarioProvider proveedor,
                                                  AuthenticationEntryPoint puntoEntrada) {
        return new FiltroAutoridadUsuario(proveedor, puntoEntrada);
    }

    /**
     * El secreto debe existir y tener texto. No basta {@code @ConditionalOnProperty}: esa
     * condición también casa con un valor vacío ({@code secreto: ${JWT_SECRETO:}}), y un
     * decoder con clave vacía rompería el arranque ({@code SecretKeySpec} rechaza las
     * claves de longitud cero). Ver T4 del registro de la feature.
     */
    static final class SecretoConfigurado implements Condition {

        @Override
        public boolean matches(ConditionContext contexto, AnnotatedTypeMetadata metadatos) {
            String secreto = contexto.getEnvironment().getProperty("app.seguridad.jwt.secreto");
            return secreto != null && !secreto.isBlank();
        }
    }
}
