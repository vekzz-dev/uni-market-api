package com.unimarket.api.shared.pagination;

import com.unimarket.api.shared.error.EstructuraInvalidaException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Cursor opaco compuesto (D4) con dos variantes según el criterio de orden del catálogo:
 * fecha ({@code {"f":<epochMicros>,"i":"<uuid>"}}) y precio
 * ({@code {"p":"<decimal-plano>","i":"<uuid>"}}). El formato es interno — el cliente solo
 * lo transporta (SDD §6) — y cualquier entrada ilegible (o de la variante equivocada) es
 * un 400 estructural.
 */
@Component
public class CursorCodec {

    private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODIFICADOR = Base64.getUrlDecoder();
    private static final long MICROS_POR_SEGUNDO = 1_000_000L;
    private static final long NANOS_POR_MICRO = 1_000L;
    private static final String DETALLE_INVALIDO = "El cursor no es válido.";

    private final ObjectMapper mapeador;

    public CursorCodec(ObjectMapper mapeador) {
        this.mapeador = mapeador;
    }

    /** Codifica el registro de fecha; el resultado es un string opaco para el cliente. */
    public String codificar(RegistroCursor registro) {
        long micros = registro.fechaCreacion().getEpochSecond() * MICROS_POR_SEGUNDO
                + registro.fechaCreacion().getNano() / NANOS_POR_MICRO;
        byte[] json = mapeador.writeValueAsBytes(new CursorFechaSerializado(micros, registro.id().toString()));
        return CODIFICADOR.encodeToString(json);
    }

    /** Codifica el registro de precio (orden alternativo del catálogo, D4). */
    public String codificarPrecio(BigDecimal precio, UUID id) {
        byte[] json = mapeador.writeValueAsBytes(new CursorPrecioSerializado(precio.toPlainString(), id.toString()));
        return CODIFICADOR.encodeToString(json);
    }

    /**
     * Decodifica el cursor de fecha con validación estricta de forma y contenido.
     *
     * @throws EstructuraInvalidaException si no es base64url, no es un objeto JSON con
     *         {@code f}/{@code i} válidos, o el UUID no es parseable
     */
    public RegistroCursor decodificar(String cursor) {
        JsonNode raiz = leerJson(cursor);
        JsonNode micros = raiz.get("f");
        JsonNode id = raiz.get("i");
        if (micros == null || !micros.isIntegralNumber() || id == null || !id.isString()) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
        try {
            return new RegistroCursor(instanteDe(micros.longValue()), UUID.fromString(id.stringValue()));
        } catch (IllegalArgumentException | DateTimeException excepcion) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
    }

    /**
     * Decodifica el cursor de precio con la misma validación estricta; un token de la
     * variante de fecha no decodifica aquí (y viceversa).
     *
     * @throws EstructuraInvalidaException si el formato no corresponde a la variante de precio
     */
    public RegistroCursorPrecio decodificarPrecio(String cursor) {
        JsonNode raiz = leerJson(cursor);
        JsonNode precio = raiz.get("p");
        JsonNode id = raiz.get("i");
        if (precio == null || !precio.isString() || id == null || !id.isString()) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
        try {
            return new RegistroCursorPrecio(new BigDecimal(precio.stringValue()), UUID.fromString(id.stringValue()));
        } catch (IllegalArgumentException excepcion) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
    }

    private JsonNode leerJson(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
        JsonNode raiz;
        try {
            raiz = mapeador.readTree(DECODIFICADOR.decode(cursor));
        } catch (IllegalArgumentException | JacksonException excepcion) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new EstructuraInvalidaException(DETALLE_INVALIDO);
        }
        return raiz;
    }

    private static Instant instanteDe(long micros) {
        return Instant.ofEpochSecond(
                Math.floorDiv(micros, MICROS_POR_SEGUNDO),
                Math.floorMod(micros, MICROS_POR_SEGUNDO) * NANOS_POR_MICRO);
    }

    /** Forma de wire del cursor de fecha: compacta y estable (D4). */
    private record CursorFechaSerializado(long f, String i) {
    }

    /** Forma de wire del cursor de precio (D4). */
    private record CursorPrecioSerializado(String p, String i) {
    }
}
