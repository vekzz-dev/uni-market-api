package com.unimarket.api.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Acción prohibida para el actor autenticado → {@code 403}: anti auto-decisión en
 * moderación/admin (R5, R14), ownership del recurso (R7).
 */
public class ProhibidoException extends ApiException {

    public ProhibidoException(String detalle) {
        super(HttpStatus.FORBIDDEN, "Acción no permitida", detalle);
    }
}
