package com.unimarket.api.identity;

import com.unimarket.api.shared.security.FiltroAutoridadUsuario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Seguridad de identity (SDD §8): BCrypt para {@code hashContrasena} (R11), cadena con las
 * rutas públicas del contrato (registro, sesión, confirmación, reset, catálogo y medios)
 * y el resto autenticado, resource server JWT HS256 más el filtro de autoridad por
 * request (D3) con respuestas RFC 9457 indistinguibles (R13/R15).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ConfiguracionSeguridad {

    /** BCrypt estándar para contraseñas (mínimo 8, sin composición, R11). */
    @Bean
    PasswordEncoder codificadorContrasena() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Decoder de respaldo cuando el secreto no está configurado: rechaza todo token en
     * lugar de romper el arranque (útil en dev/test; en producción el secreto es
     * obligatorio). La condición de ausencia se evalúa contra autoconfiguraciones; como el
     * decoder HS256 del kernel vive en una {@code @Configuration} de usuario (mismo
     * orden de fase), ambos pueden coexistir y la cadena elige el real (ver abajo).
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoderRechazoTotal() {
        return new RechazoTotal();
    }

    /** Marcador del respaldo para que la cadena prefiera el decoder HS256 real. */
    static final class RechazoTotal implements JwtDecoder {

        @Override
        public org.springframework.security.oauth2.jwt.Jwt decode(String token) {
            throw new JwtException("Validación JWT no configurada: falta app.seguridad.jwt.secreto.");
        }
    }

    /**
     * CORS por configuración (SDD §8): dev permite localhost con cualquier puerto
     * ({@code http://localhost:*}); prod va vacío salvo que el operador defina
     * {@code CORS_ORIGENES} — navegador bloqueado por defecto hasta configurarlo.
     * Sin cookies (Bearer), así que {@code allowCredentials} queda en falso.
     */
    @Bean
    CorsConfigurationSource fuenteCors(
            @Value("${app.seguridad.cors.origenes-permitidos:}") String origenesCsv) {
        CorsConfiguration configuracion = new CorsConfiguration();
        configuracion.setAllowedOriginPatterns(parsearOrigenes(origenesCsv));
        configuracion.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuracion.setAllowedHeaders(List.of("*"));
        // El cliente necesita leer Location en los 201 (p. ej. crear anuncio).
        configuracion.setExposedHeaders(List.of("Location"));
        configuracion.setAllowCredentials(false);
        configuracion.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/**", configuracion);
        return fuente;
    }

    /** CSV a lista, ignorando vacíos: {@code "a, b"} → {@code ["a","b"]}. */
    static List<String> parsearOrigenes(String origenesCsv) {
        if (origenesCsv == null || origenesCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(origenesCsv.split(","))
                .map(String::strip)
                .filter(origen -> !origen.isEmpty())
                .toList();
    }

    /** Cadena de filtros: públicas del contrato abiertas, resto autenticado (stateless). */
    @Bean
    SecurityFilterChain cadenaSeguridad(
            HttpSecurity http,
            AuthenticationEntryPoint puntoEntrada,
            AccessDeniedHandler denegado,
            java.util.List<JwtDecoder> decodificadores,
            FiltroAutoridadUsuario filtroAutoridad,
            CorsConfigurationSource fuenteCors) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.cors(cors -> cors.configurationSource(fuenteCors));
        http.sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.exceptionHandling(errores -> errores
                .authenticationEntryPoint(puntoEntrada)
                .accessDeniedHandler(denegado));
        http.authorizeHttpRequests(autorizacion -> autorizacion
                .requestMatchers(HttpMethod.POST, "/v1/usuarios").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/auth/confirmar-email").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/anuncios", "/v1/anuncios/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/categorias").permitAll()
                .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                .anyRequest().authenticated());
        http.oauth2ResourceServer(recurso -> recurso
                .jwt(jwt -> jwt.decoder(decoderReal(decodificadores)))
                .authenticationEntryPoint(puntoEntrada)
                .accessDeniedHandler(denegado));
        http.addFilterAfter(filtroAutoridad, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Prefiere el decoder HS256 del kernel; el respaldo de rechazo total solo se usa si
     * es el único (sin secreto configurado).
     */
    private static JwtDecoder decoderReal(java.util.List<JwtDecoder> decodificadores) {
        return decodificadores.stream()
                .filter(decoder -> !(decoder instanceof RechazoTotal))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sin JwtDecoder disponible"));
    }
}
