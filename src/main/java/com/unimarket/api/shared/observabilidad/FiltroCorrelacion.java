package com.unimarket.api.shared.observabilidad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlación de requests (SDD §10): acepta un {@code X-Request-Id} entrante (útil detrás
 * de un proxy) o genera un UUID, lo publica en el MDC como {@code traceId} para que CADA
 * línea de log del request lo lleve, y lo devuelve en la respuesta para que el cliente
 * pueda reportar un identificador accionable.
 *
 * <p>El valor entrante se sanea con una allow-list estricta (caracteres seguros y ≤ 64):
 * un header malicioso con saltos de línea no puede inyectar líneas en los logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroCorrelacion extends OncePerRequestFilter {

    public static final String CABECERA = "X-Request-Id";
    public static final String CLAVE_MDC = "traceId";

    private static final Pattern VALOR_SEGURO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String idRecibido = solicitud.getHeader(CABECERA);
        String id = idRecibido != null && VALOR_SEGURO.matcher(idRecibido).matches()
                ? idRecibido
                : UUID.randomUUID().toString();
        MDC.put(CLAVE_MDC, id);
        try {
            respuesta.setHeader(CABECERA, id);
            cadena.doFilter(solicitud, respuesta);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }
}
