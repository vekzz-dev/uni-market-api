package com.unimarket.api.identity;

import com.unimarket.api.shared.security.Rol;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Contratos del wire de identity (openapi.yaml): wire {@code camelCase}; la validación
 * estructural responde {@code 400} y las reglas de negocio {@code 422/409/401} (SDD §6).
 */
public final class Dtos {

    private Dtos() {
    }

    /** Registro público: solo crea rol {@code miembro} (R3); email único activo (R2). */
    public record RegistroRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 1, max = 255) String nombre,
            @Size(min = 8, max = 255) String contrasena) {
    }

    /** Login: email normalizado; exige confirmado+habilitado+sin baja (R15). */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String contrasena) {
    }

    /** Rotación del refresh opaco (R12). */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** Logout: revocación idempotente, siempre {@code 204} (R12). */
    public record LogoutRequest(@NotBlank String refreshToken) {
    }

    /** Solicitud de reset: respuesta idéntica exista o no la cuenta (R13). */
    public record SolicitudResetRequest(@NotBlank @Email String email) {
    }

    /** Consumo del reset: token de 60 min de un solo uso (R11). */
    public record ConfirmarResetRequest(
            @NotBlank String token,
            @Size(min = 8, max = 255) String nuevaContrasena) {
    }

    /** Cambio autenticado: verifica la actual y aplica la política uniforme (R11). */
    public record CambiarContrasenaRequest(
            @NotBlank String contrasenaActual,
            @Size(min = 8, max = 255) String nuevaContrasena) {
    }

    /** Perfil: el email es de solo lectura tras el registro (R2). */
    public record ActualizarPerfilRequest(
            @NotBlank @Size(min = 1, max = 255) String nombre) {
    }

    /** Perfil vigente: jamás incluye {@code hashContrasena} (R11). */
    public record UsuarioRespuesta(
            UUID id,
            String email,
            String nombre,
            String urlAvatar,
            Rol rol,
            Instant fechaVerificacionEmail,
            Instant fechaCreacion) {

        /** Proyección de la fila a su forma pública. */
        public static UsuarioRespuesta de(Usuario usuario) {
            return new UsuarioRespuesta(
                    usuario.getId(),
                    usuario.getEmail(),
                    usuario.getNombre(),
                    usuario.getUrlAvatar(),
                    usuario.getRol(),
                    usuario.getFechaVerificacionEmail(),
                    usuario.getFechaCreacion());
        }
    }

    /** Sesión emitida: el access vive en memoria del cliente, el refresh se persiste (R12). */
    public record SesionRespuesta(
            String accessToken,
            String refreshToken,
            long expiraEnSegundos,
            UsuarioRespuesta usuario) {
    }

    /** Respuestas genéricas idénticas (confirmación solicitada, reset no-enumerable). */
    public record MensajeRespuesta(String mensaje) {
    }
}
