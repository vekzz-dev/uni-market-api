package com.unimarket.api.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sesión y recuperación (openapi.yaml, tag Auth): login, refresh rotativo, logout,
 * confirmación de email y restablecimiento no-enumerable. Las rutas son públicas; el
 * límite de tasa (Q1/R13) protege login y solicitud de reset.
 */
@RestController
@RequestMapping("/v1/auth")
public class ControladorAuth {

    private final ServicioRegistro registro;
    private final ServicioSesion sesion;
    private final ServicioRecuperacion recuperacion;
    private final LimitadorTasa tasa;

    public ControladorAuth(
            ServicioRegistro registro,
            ServicioSesion sesion,
            ServicioRecuperacion recuperacion,
            LimitadorTasa tasa) {
        this.registro = registro;
        this.sesion = sesion;
        this.recuperacion = recuperacion;
        this.tasa = tasa;
    }

    /** Inicia sesión: exige cuenta confirmada, habilitada y sin baja (R15). */
    @PostMapping("/login")
    public ResponseEntity<Dtos.SesionRespuesta> login(
            @Valid @RequestBody Dtos.LoginRequest cuerpo, HttpServletRequest solicitud) {
        tasa.exigir("login", solicitud.getRemoteAddr(), cuerpo.email());
        return ResponseEntity.ok(sesion.login(cuerpo.email(), cuerpo.contrasena()));
    }

    /** Rota el refresh opaco con la misma familia (R12). */
    @PostMapping("/refresh")
    public ResponseEntity<Dtos.SesionRespuesta> refresh(@Valid @RequestBody Dtos.RefreshRequest cuerpo) {
        return ResponseEntity.ok(sesion.refresh(cuerpo.refreshToken()));
    }

    /** Revoca el refresh server-side de forma idempotente: siempre {@code 204} (R12). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody Dtos.LogoutRequest cuerpo) {
        sesion.logout(cuerpo.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Confirma el email con el token crudo de un solo uso (R11). */
    @GetMapping("/confirmar-email")
    public ResponseEntity<Dtos.MensajeRespuesta> confirmarEmail(
            @RequestParam(value = "token", required = false) String token) {
        registro.confirmarEmail(exigirToken(token));
        return ResponseEntity.ok(new Dtos.MensajeRespuesta("Email confirmado."));
    }

    /** Solicita el reset: responde {@code 202} idéntico exista o no la cuenta (R13). */
    @PostMapping("/restablecer-contrasena")
    public ResponseEntity<Dtos.MensajeRespuesta> solicitarReset(
            @Valid @RequestBody Dtos.SolicitudResetRequest cuerpo, HttpServletRequest solicitud) {
        tasa.exigir("reset", solicitud.getRemoteAddr(), cuerpo.email());
        recuperacion.solicitar(cuerpo.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Dtos.MensajeRespuesta(
                        "Si la cuenta existe, recibirás un correo para restablecer tu contraseña."));
    }

    /** Consume el reset de un solo uso (R11). */
    @PostMapping("/restablecer-contrasena/confirmar")
    public ResponseEntity<Void> confirmarReset(@Valid @RequestBody Dtos.ConfirmarResetRequest cuerpo) {
        recuperacion.confirmar(cuerpo.token(), cuerpo.nuevaContrasena());
        return ResponseEntity.noContent().build();
    }

    private static String exigirToken(String token) {
        if (token == null || token.isBlank()) {
            throw new com.unimarket.api.shared.error.EstructuraInvalidaException(
                    "El parámetro 'token' es requerido.");
        }
        return token;
    }
}
