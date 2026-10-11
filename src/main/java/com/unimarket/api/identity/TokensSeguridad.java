package com.unimarket.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens opacos y su hash (R11/R12): el valor crudo viaja una sola vez (email o respuesta
 * HTTP) y en la BD solo vive el SHA-256, buscado por hash del valor presentado.
 */
public final class TokensSeguridad {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private TokensSeguridad() {
    }

    /** Valor crudo opaco de 32 bytes aleatorios en base64url sin relleno. */
    public static String generarCrudo() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 en hexadecimal del valor crudo: lo único que se persiste (R11). */
    public static String sha256Hex(String crudo) {
        try {
            MessageDigest resumen = MessageDigest.getInstance("SHA-256");
            byte[] hash = resumen.digest(crudo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException excepcion) {
            throw new IllegalStateException("SHA-256 no disponible en la plataforma", excepcion);
        }
    }
}
