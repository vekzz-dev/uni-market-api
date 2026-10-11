package com.unimarket.api.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Valor que viola una regla de negocio → {@code 422} (SDD §6): motivo vacío, texto en
 * blanco, auto-conversación. Es el par explícito del {@code 400} estructural.
 */
public class ReglaDeNegocioException extends ApiException {

    public ReglaDeNegocioException(String detalle) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "Regla de negocio violada", detalle);
    }
}
