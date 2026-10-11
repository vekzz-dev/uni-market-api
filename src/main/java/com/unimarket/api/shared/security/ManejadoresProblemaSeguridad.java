package com.unimarket.api.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Respuestas RFC 9457 del kernel de seguridad (SDD §8): 401 y 403 en
 * {@code application/problem+json}. Los cuerpos son constantes a propósito: el rechazo
 * debe ser indistinguible (no revela si la cuenta existe, está inhabilitada o dada de
 * baja, R13/R15) y no depende de la serialización ni de la causa.
 */
@Configuration(proxyBeanMethods = false)
public class ManejadoresProblemaSeguridad {

    /** 401 genérico e indistinguible de credenciales inválidas (R13, R15). */
    static final String CUERPO_401 = """
            {"type":"about:blank","title":"No autenticado","status":401,"detail":"Credenciales inválidas o sesión no vigente."}""";

    /** 403 de autorización: autenticado pero sin permisos sobre la acción (R5, R14). */
    static final String CUERPO_403 = """
            {"type":"about:blank","title":"Acceso denegado","status":403,"detail":"No tienes permisos para realizar esta acción."}""";

    @Bean
    AuthenticationEntryPoint puntoEntradaProblema() {
        return (solicitud, respuesta, excepcion) -> escribirProblema(respuesta, HttpStatus.UNAUTHORIZED, CUERPO_401);
    }

    @Bean
    AccessDeniedHandler manejadorAccesoDenegado() {
        return (solicitud, respuesta, excepcion) -> escribirProblema(respuesta, HttpStatus.FORBIDDEN, CUERPO_403);
    }

    private static void escribirProblema(HttpServletResponse respuesta, HttpStatus estado, String cuerpo)
            throws IOException {
        respuesta.setStatus(estado.value());
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.getWriter().write(cuerpo);
    }
}
