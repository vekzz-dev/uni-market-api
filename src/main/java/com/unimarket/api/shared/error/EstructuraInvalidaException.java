package com.unimarket.api.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Problema estructural de la solicitud → {@code 400} (SDD §6): JSON malformado, parámetro
 * fuera de dominio o rango, cursor ilegible. Bean Validation responde por su cuenta; esta
 * excepción cubre los casos que el código detecta antes o fuera del binding.
 */
public class EstructuraInvalidaException extends ApiException {

    public EstructuraInvalidaException(String detalle) {
        super(HttpStatus.BAD_REQUEST, "Solicitud inválida", detalle);
    }
}
