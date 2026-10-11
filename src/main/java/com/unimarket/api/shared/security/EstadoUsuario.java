package com.unimarket.api.shared.security;

import java.util.UUID;

/**
 * Estado vigente del usuario para autorizar el request (D3): rol, habilitación y baja se
 * leen de la BD en cada petición autenticada; el claim del JWT solo aporta la identidad.
 * {@code eliminado} es la baja soft-delete del propio usuario (R15).
 */
public record EstadoUsuario(UUID id, Rol rol, boolean habilitado, boolean eliminado) {
}
