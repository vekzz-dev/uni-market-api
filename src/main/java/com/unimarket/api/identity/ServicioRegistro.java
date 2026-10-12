package com.unimarket.api.identity;

import com.unimarket.api.notifications.EventosCorreo;
import com.unimarket.api.notifications.PropiedadesNotificaciones;
import com.unimarket.api.shared.error.ConflictoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.security.Rol;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;

/**
 * Registro y confirmación de email (SDD §5.1): normaliza el email a minúsculas (R2),
 * guarda BCrypt con rol {@code miembro} (R3), emite token de 24 h hasheado invalidando
 * previos en la misma transacción (R11), reenvía con cooldown (PRD §5, N5) y publica
 * el envío post-commit sin crear sesión.
 */
@Service
public class ServicioRegistro {

    private final UsuarioRepository usuarios;
    private final TokenVerificacionEmailRepository verificaciones;
    private final PasswordEncoder contrasenas;
    private final ApplicationEventPublisher eventos;
    private final PropiedadesNotificaciones notificaciones;

    public ServicioRegistro(
            UsuarioRepository usuarios,
            TokenVerificacionEmailRepository verificaciones,
            PasswordEncoder contrasenas,
            ApplicationEventPublisher eventos,
            PropiedadesNotificaciones notificaciones) {
        this.usuarios = usuarios;
        this.verificaciones = verificaciones;
        this.contrasenas = contrasenas;
        this.eventos = eventos;
        this.notificaciones = notificaciones;
    }

    /**
     * Registra la cuenta y programa el email de confirmación (evento post-commit).
     *
     * @return el perfil creado, sin tokens de sesión
     * @throws ConflictoException si el email ya está activo en cualquier caso (R2 → 409)
     */
    @Transactional
    public Dtos.UsuarioRespuesta registrar(String emailCrudo, String nombreCrudo, String contrasena) {
        String email = normalizarEmail(emailCrudo);
        exigirPolitica(contrasena);
        if (usuarios.existsByEmailIgnoreCaseAndFechaEliminacionIsNull(email)) {
            throw new ConflictoException("El email ya está registrado.");
        }
        Usuario usuario = new Usuario();
        usuario.setEmail(email);
        usuario.setNombre(nombreCrudo.strip());
        usuario.setHashContrasena(contrasenas.encode(contrasena));
        usuario.setRol(Rol.miembro);
        try {
            usuario = usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException duplicado) {
            // Carrera contra el índice parcial ux_usuarios_email_activo: mismo 409 (R2).
            throw new ConflictoException("El email ya está registrado.");
        }
        emitirToken(usuario);
        return Dtos.UsuarioRespuesta.de(usuario);
    }

    /**
     * Consume el token de confirmación en una transacción: fija {@code fechaUso} y la
     * verificación del usuario de un solo golpe (R11).
     *
     * @throws ReglaDeNegocioException si es inválido, expiró o ya se usó (422)
     */
    @Transactional
    public void confirmarEmail(String tokenCrudo) {
        TokenVerificacionEmail token = verificaciones
                .findByHashToken(TokensSeguridad.sha256Hex(tokenCrudo))
                .filter(t -> t.activo(Instant.now()))
                .orElseThrow(() -> new ReglaDeNegocioException(
                        "El token es inválido, expiró o ya fue usado."));
        Instant ahora = Instant.now();
        token.marcarUso(ahora);
        usuarios.findById(token.getUsuarioId()).ifPresent(usuario -> {
            if (usuario.activa() && usuario.getFechaVerificacionEmail() == null) {
                usuario.setFechaVerificacionEmail(ahora);
            }
        });
    }

    /**
     * Reenvía la confirmación con cooldown configurable (PRD §5, N5): no revela nada
     * (R13 — el llamador responde genérico), ignora cuentas inexistentes, confirmadas,
     * inhabilitadas o dadas de baja, y dentro del cooldown no reemite token.
     */
    @Transactional
    public void reenviarConfirmacion(String emailCrudo) {
        String email = normalizarEmail(emailCrudo);
        usuarios.findByEmailIgnoreCaseAndFechaEliminacionIsNull(email)
                .filter(usuario -> usuario.activa() && usuario.getFechaVerificacionEmail() == null)
                .ifPresent(usuario -> {
                    Instant ahora = Instant.now();
                    boolean dentroDelCooldown = verificaciones
                            .findByUsuarioIdAndFechaUsoIsNull(usuario.getId())
                            .stream()
                            .map(TokenVerificacionEmail::getFechaCreacion)
                            .filter(fecha -> fecha != null)
                            .max(Comparator.naturalOrder())
                            .map(ultima -> ultima.isAfter(ahora.minus(notificaciones.cooldownReenvioOPorDefecto())))
                            .orElse(false);
                    if (!dentroDelCooldown) {
                        emitirToken(usuario);
                    }
                });
    }

    /** Emite el token de 24 h invalidando los activos previos en la misma transacción. */
    private void emitirToken(Usuario usuario) {
        Instant ahora = Instant.now();
        verificaciones.findByUsuarioIdAndFechaUsoIsNull(usuario.getId())
                .forEach(previo -> previo.marcarUso(ahora));
        verificaciones.flush();
        String crudo = TokensSeguridad.generarCrudo();
        TokenVerificacionEmail token = new TokenVerificacionEmail();
        token.setUsuarioId(usuario.getId());
        token.setHashToken(TokensSeguridad.sha256Hex(crudo));
        token.setFechaExpiracion(ahora.plus(24, ChronoUnit.HOURS));
        verificaciones.save(token);
        eventos.publishEvent(
                new EventosCorreo.ConfirmacionRequerida(usuario.getId(), usuario.getEmail(), crudo));
    }

    /** Email recortado y en minúsculas: `A@x.com` y `a@x.com` son la misma cuenta (R2). */
    static String normalizarEmail(String emailCrudo) {
        return emailCrudo.strip().toLowerCase(Locale.ROOT);
    }

    /** Política uniforme NIST 800-63B: mínimo 8, sin composición (R11). */
    static void exigirPolitica(String contrasena) {
        if (contrasena == null || contrasena.length() < 8) {
            throw new ReglaDeNegocioException("La contraseña debe tener al menos 8 caracteres.");
        }
    }
}
