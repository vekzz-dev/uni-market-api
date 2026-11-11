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

    /**
     * PIN de 6 dígitos para confirmación y reset (R11, issue #8): se genera uniforme sobre
     * 000000–999999 con {@link SecureRandom}. Sin sesgo de módulo: el rango es exactamente
     * 10^6, que es el mismo que usa {@code nextInt(bound)}.
     */
    public static String generarPin() {
        return "%06d".formatted(ALEATORIO.nextInt(1_000_000));
    }

    /**
     * Compara el PIN presentado con el hash persistido en tiempo constante. Un PIN corto
     * invita a medir tiempos; {@code MessageDigest.isEqual} evita la comparación temprana.
     */
    public static boolean pinCoincide(String pin, String hashPersistido) {
        return MessageDigest.isEqual(
                sha256Hex(pin).getBytes(StandardCharsets.UTF_8),
                hashPersistido.getBytes(StandardCharsets.UTF_8));
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
