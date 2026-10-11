package com.unimarket.api.identity;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de tasa en memoria (Q1/R13, N3): ventana deslizante de 5 intentos por minuto por
 * propósito, IP y email. Sin librería externa; basta en despliegue único (SDD N3).
 *
 * <p>Cada intento cuenta ANTES de procesarse, de modo que el sexto responde {@code 429}
 * aunque la solicitud habría fallado por negocio (p. ej. email duplicado).
 */
@Component
public class LimitadorTasa {

    /** Intentos permitidos por ventana y propósito. */
    static final int MAX_INTENTOS = 5;

    private static final Duration VENTANA = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, ArrayDeque<Instant>> intentos = new ConcurrentHashMap<>();

    /**
     * Registra un intento y falla con {@code 429} si la ventana está agotada.
     *
     * @param proposito login, registro o reset (espacio de claves separado por flujo)
     * @param ip        IP remota del cliente
     * @param email     email crudo de la solicitud (se normaliza para la clave)
     */
    public void exigir(String proposito, String ip, String email) {
        String clave = proposito + "|" + ip + "|" + email.strip().toLowerCase(Locale.ROOT);
        Instant ahora = Instant.now();
        Instant corte = ahora.minus(VENTANA);
        ArrayDeque<Instant> cola = intentos.computeIfAbsent(clave, k -> new ArrayDeque<>());
        synchronized (cola) {
            while (!cola.isEmpty() && cola.peekFirst().isBefore(corte)) {
                cola.pollFirst();
            }
            if (cola.size() >= MAX_INTENTOS) {
                throw new DemasiadasPeticionesException(
                        "Demasiados intentos; espera un minuto e intenta de nuevo.");
            }
            cola.addLast(ahora);
        }
    }
}
