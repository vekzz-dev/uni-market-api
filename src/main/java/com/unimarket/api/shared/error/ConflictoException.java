package com.unimarket.api.shared.error;

import org.springframework.http.HttpStatus;

/**
 * La solicitud choca con el estado actual del recurso → {@code 409}: email ya registrado,
 * UNIQUE natural violada.
 */
public class ConflictoException extends ApiException {

    public ConflictoException(String detalle) {
        super(HttpStatus.CONFLICT, "Conflicto con el estado actual", detalle);
    }
}
