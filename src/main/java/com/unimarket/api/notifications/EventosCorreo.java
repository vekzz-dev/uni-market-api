package com.unimarket.api.notifications;

import java.util.UUID;

/**
 * Eventos de dominio para el envío post-commit (D8): {@code identity} los publica dentro
 * de su transacción y {@code notifications} los consume tras el commit. Viven aquí (y no
 * en {@code identity}) para que la dependencia apunte en una sola dirección
 * {@code identity → notifications} sin ciclos (frontera ArchUnit, SDD §3).
 */
public final class EventosCorreo {

    /**
     * Vigencia del PIN de un solo uso (issue #8). Vive aquí —y no en identity— porque la
     * plantilla del correo la necesita y {@code identity} ya depende de {@code notifications}:
     * al revés se rompería la frontera de módulos (ArchUnit).
     */
    public static final int VIGENCIA_PIN_MINUTOS = 15;

    /** Texto de vigencia que se muestra en el correo. */
    public static final String VIGENCIA_PIN_TEXTO = VIGENCIA_PIN_MINUTOS + " minutos";

    private EventosCorreo() {
    }

    /** Registro creado: confirma el email con un PIN de 6 dígitos (15 min, R11). */
    public record ConfirmacionRequerida(UUID usuarioId, String email, String nombre, String pin) {
    }

    /** Reset solicitado en cuenta activa: PIN de 6 dígitos (15 min, R11). */
    public record RestablecimientoRequerido(UUID usuarioId, String email, String nombre, String pin) {
    }
}
