package com.unimarket.api.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Raíz de los errores de la API: {@link ApiExceptionHandler} la traduce a un problema
 * RFC 9457 ({@code application/problem+json}, SDD §6). El mensaje es el <em>detalle
 * seguro</em> que viaja al cliente: nunca incluye stack traces ni datos internos.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus estado;
    private final String titulo;

    protected ApiException(HttpStatus estado, String titulo, String detalle) {
        super(detalle);
        this.estado = estado;
        this.titulo = titulo;
    }

    /** Estado HTTP con el que se responde. */
    public HttpStatus estado() {
        return estado;
    }

    /** Título del problema; en errores indistinguibles no revela la causa (R13). */
    public String titulo() {
        return titulo;
    }
}
