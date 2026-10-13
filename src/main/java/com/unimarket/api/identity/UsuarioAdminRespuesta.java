package com.unimarket.api.identity;

import com.unimarket.api.shared.security.Rol;

import java.time.Instant;
import java.util.UUID;

/**
 * Respuesta administrativa de usuario (openapi: {@code UsuarioAdmin}): perfil público más
 * {@code habilitado}. Jamás incluye {@code hashContrasena} (R11) ni {@code fechaEliminacion}
 * (el panel nunca la escribe ni la expone, R15).
 */
public record UsuarioAdminRespuesta(
        UUID id,
        String email,
        String nombre,
        String urlAvatar,
        Rol rol,
        Instant fechaVerificacionEmail,
        Instant fechaCreacion,
        boolean habilitado) {

    public static UsuarioAdminRespuesta de(Usuario usuario) {
        return new UsuarioAdminRespuesta(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getNombre(),
                usuario.getUrlAvatar(),
                usuario.getRol(),
                usuario.getFechaVerificacionEmail(),
                usuario.getFechaCreacion(),
                usuario.isHabilitado());
    }
}
