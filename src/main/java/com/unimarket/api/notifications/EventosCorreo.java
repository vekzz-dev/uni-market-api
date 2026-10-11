package com.unimarket.api.notifications;

import java.util.UUID;

/**
 * Eventos de dominio para el envío post-commit (D8): {@code identity} los publica dentro
 * de su transacción y {@code notifications} los consume tras el commit. Viven aquí (y no
 * en {@code identity}) para que la dependencia apunte en una sola dirección
 * {@code identity → notifications} sin ciclos (frontera ArchUnit, SDD §3).
 */
public final class EventosCorreo {

    private EventosCorreo() {
    }

    /** Registro creado: confirma el email con el token crudo (ventana 24 h, R11). */
    public record ConfirmacionRequerida(UUID usuarioId, String email, String tokenCrudo) {
    }

    /** Reset solicitado en cuenta activa: token crudo (ventana 60 min, R11). */
    public record RestablecimientoRequerido(UUID usuarioId, String email, String tokenCrudo) {
    }
}
