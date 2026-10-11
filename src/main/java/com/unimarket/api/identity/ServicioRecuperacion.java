package com.unimarket.api.identity;

import com.unimarket.api.notifications.EventosCorreo;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Restablecimiento y cambio de contraseña (R11/R13): la solicitud responde igual exista o
 * no la cuenta y solo emite token+email si está activa; el consumo es de un solo uso con
 * política uniforme de 8+; el cambio autenticado verifica la clave actual.
 */
@Service
public class ServicioRecuperacion {

    private final UsuarioRepository usuarios;
    private final TokenRestablecimientoRepository restablecimientos;
    private final ServicioSesion sesiones;
    private final PasswordEncoder contrasenas;
    private final ApplicationEventPublisher eventos;

    public ServicioRecuperacion(
            UsuarioRepository usuarios,
            TokenRestablecimientoRepository restablecimientos,
            ServicioSesion sesiones,
            PasswordEncoder contrasenas,
            ApplicationEventPublisher eventos) {
        this.usuarios = usuarios;
        this.restablecimientos = restablecimientos;
        this.sesiones = sesiones;
        this.contrasenas = contrasenas;
        this.eventos = eventos;
    }

    /**
     * Solicita el reset (R13): siempre termina igual; solo crea token de 60 min y programa
     * el email cuando la cuenta existe, está habilitada y sin baja.
     */
    @Transactional
    public void solicitar(String emailCrudo) {
        String email = ServicioRegistro.normalizarEmail(emailCrudo);
        usuarios.findByEmailIgnoreCaseAndFechaEliminacionIsNull(email)
                .filter(Usuario::isHabilitado)
                .ifPresent(this::emitirToken);
    }

    /**
     * Consume el reset de un solo uso (R11): fija {@code fechaUso}, escribe el nuevo
     * BCrypt y corta las sesiones previas.
     *
     * @throws ReglaDeNegocioException si el token es inválido/expiró/se usó o la clave
     *                                 viola la política (422)
     */
    @Transactional
    public void confirmar(String tokenCrudo, String nuevaContrasena) {
        ServicioRegistro.exigirPolitica(nuevaContrasena);
        TokenRestablecimientoContrasena token = restablecimientos
                .findByHashToken(TokensSeguridad.sha256Hex(tokenCrudo))
                .filter(t -> t.activo(Instant.now()))
                .orElseThrow(() -> new ReglaDeNegocioException(
                        "El token es inválido, expiró o ya fue usado."));
        token.marcarUso(Instant.now());
        Usuario usuario = usuarios.findById(token.getUsuarioId())
                .filter(Usuario::activa)
                .orElseThrow(() -> new ReglaDeNegocioException(
                        "El token es inválido, expiró o ya fue usado."));
        usuario.setHashContrasena(contrasenas.encode(nuevaContrasena));
        sesiones.revocarTodos(usuario.getId());
    }

    /**
     * Cambio autenticado: verifica la actual con BCrypt y aplica la política a la nueva.
     *
     * @throws ReglaDeNegocioException si la actual no coincide o la nueva viola la
     *                                 política (422)
     */
    @Transactional
    public void cambiarContrasena(UUID usuarioId, String actual, String nueva) {
        ServicioRegistro.exigirPolitica(nueva);
        Usuario usuario = usuarios.findById(usuarioId)
                .filter(Usuario::activa)
                .orElseThrow(() -> new ReglaDeNegocioException("La cuenta no está vigente."));
        if (!contrasenas.matches(actual, usuario.getHashContrasena())) {
            throw new ReglaDeNegocioException("La contraseña actual es incorrecta.");
        }
        usuario.setHashContrasena(contrasenas.encode(nueva));
    }

    /** Emite el token de 60 min invalidando los activos previos en la misma transacción. */
    private void emitirToken(Usuario usuario) {
        Instant ahora = Instant.now();
        restablecimientos.findByUsuarioIdAndFechaUsoIsNull(usuario.getId())
                .forEach(previo -> previo.marcarUso(ahora));
        restablecimientos.flush();
        String crudo = TokensSeguridad.generarCrudo();
        TokenRestablecimientoContrasena token = new TokenRestablecimientoContrasena();
        token.setUsuarioId(usuario.getId());
        token.setHashToken(TokensSeguridad.sha256Hex(crudo));
        token.setFechaExpiracion(ahora.plus(60, ChronoUnit.MINUTES));
        restablecimientos.save(token);
        eventos.publishEvent(
                new EventosCorreo.RestablecimientoRequerido(usuario.getId(), usuario.getEmail(), crudo));
    }
}
