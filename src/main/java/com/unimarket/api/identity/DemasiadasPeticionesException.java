package com.unimarket.api.identity;

import com.unimarket.api.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Límite de tasa excedido (Q1/R13) → {@code 429} en {@code application/problem+json}:
 * login, registro y solicitud de reset comparten la ventana de 5 intentos por minuto por
 * IP y email.
 */
public class DemasiadasPeticionesException extends ApiException {

    public DemasiadasPeticionesException(String detalle) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas peticiones", detalle);
    }
}
