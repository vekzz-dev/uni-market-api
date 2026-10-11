package com.unimarket.api.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Recurso inexistente o no visible para el actor → {@code 404}. El detalle del catálogo
 * público no distingue "no existe" de "no visible" (R4).
 */
public class NoEncontradoException extends ApiException {

    public NoEncontradoException(String detalle) {
        super(HttpStatus.NOT_FOUND, "Recurso no encontrado", detalle);
    }
}
