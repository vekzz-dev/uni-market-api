package com.unimarket.api.identity;

import com.unimarket.api.shared.security.PropiedadesJwt;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sesión (SDD §5.2, R12): login que emite JWT HS256 de 15 min + refresh opaco de 30 días,
 * refresh con rotación transaccional y detección de reuso (revoca la familia entera), y
 * logout con revocación idempotente.
 *
 * <p>Todo rechazo de sesión responde el {@code 401} genérico indistinguible (R13/R15):
 * el mensaje nunca revela si la cuenta existe, está inhabilitada o fue dada de baja.
 *
 * <p>El codificador JWT se construye del mismo secreto externalizado que el decoder del
 * kernel (D2, {@code app.seguridad.jwt}): si el secreto falta, la emisión falla al usarse,
 * nunca en el arranque.
 */
@Service
public class ServicioSesion {

    /** Hash BCrypt ficticio para que el login siempre cueste lo mismo exista o no (R13). */
    private static final String HASH_FICTICIO =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("ficticio-solo-para-timing");

    private final UsuarioRepository usuarios;
    private final TokenRefreshRepository refreshes;
    private final PasswordEncoder contrasenas;
    private final PropiedadesJwt propiedades;

    public ServicioSesion(
            UsuarioRepository usuarios,
            TokenRefreshRepository refreshes,
            PasswordEncoder contrasenas,
            PropiedadesJwt propiedades) {
        this.usuarios = usuarios;
        this.refreshes = refreshes;
        this.contrasenas = contrasenas;
        this.propiedades = propiedades;
    }

    /**
     * Valida email normalizado + BCrypt + confirmado + habilitado + sin baja (R15) y
     * emite el par de sesión con una familia de rotación nueva.
     */
    @Transactional
    public Dtos.SesionRespuesta login(String emailCrudo, String contrasena) {
        String email = ServicioRegistro.normalizarEmail(emailCrudo);
        Optional<Usuario> candidato = usuarios.findByEmailIgnoreCaseAndFechaEliminacionIsNull(email);
        String hash = candidato.map(Usuario::getHashContrasena).orElse(HASH_FICTICIO);
        boolean coincide = contrasenas.matches(contrasena, hash);
        Usuario usuario = candidato.filter(Usuario::vigenteParaSesion).orElse(null);
        if (!coincide || usuario == null) {
            throw new BadCredentialsException("Credenciales inválidas o sesión no vigente.");
        }
        return emitir(usuario, UUID.randomUUID());
    }

    /**
     * Rota el refresh en una transacción (R12): marca el uso del anterior e inserta el
     * nuevo con la misma {@code familiaId}. Un token ya usado (posible robo) revoca TODA
     * la familia; expirado, revocado o desconocido responde {@code 401} sin más efectos.
     *
     * <p>{@code noRollbackFor}: la revocación ante reuso debe commitear aunque el método
     * termine en {@code 401}; sin esto el rollback borraría la revocación y la familia
     * seguiría viva.
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Dtos.SesionRespuesta refresh(String tokenCrudo) {
        Instant ahora = Instant.now();
        TokenRefresh anterior = refreshes.findByHashToken(TokensSeguridad.sha256Hex(tokenCrudo))
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas o sesión no vigente."));
        if (anterior.reutilizado() || anterior.getFechaRevocacion() != null) {
            revocarFamilia(anterior.getFamiliaId(), ahora);
            throw new BadCredentialsException("Credenciales inválidas o sesión no vigente.");
        }
        if (!anterior.activo(ahora)) {
            throw new BadCredentialsException("Credenciales inválidas o sesión no vigente.");
        }
        Usuario usuario = usuarios.findById(anterior.getUsuarioId())
                .filter(Usuario::vigenteParaSesion)
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas o sesión no vigente."));
        return emitir(usuario, anterior.getFamiliaId());
    }

    /** Revoca el refresh server-side; inexistente o ya revocado sigue siendo {@code 204}. */
    @Transactional
    public void logout(String tokenCrudo) {
        Instant ahora = Instant.now();
        refreshes.findByHashToken(TokensSeguridad.sha256Hex(tokenCrudo)).ifPresent(token -> {
            if (token.getFechaRevocacion() == null) {
                token.revocar(ahora);
            }
        });
    }

    /** Revoca server-side todos los refresh del usuario (baja R15, reset de clave). */
    @Transactional
    public void revocarTodos(UUID usuarioId) {
        Instant ahora = Instant.now();
        refreshes.findByUsuarioIdAndFechaUsoIsNull(usuarioId)
                .forEach(token -> token.revocar(ahora));
        refreshes.flush();
    }

    /** Marca uso+revocación en cada miembro de la familia (R12, ante reuso detectado). */
    private void revocarFamilia(UUID familiaId, Instant ahora) {
        refreshes.findByFamiliaId(familiaId).forEach(token -> {
            if (token.getFechaRevocacion() == null) {
                token.revocar(ahora);
            }
        });
    }

    /**
     * Emite el par con una familia dada, invalidando los refresh activos previos del
     * usuario en la misma transacción (un solo activo por usuario, DBDD §3.1): el UPDATE
     * se flushea ANTES del INSERT porque Hibernate ordena los inserts primero y el
     * índice parcial {@code ux_tokens_refresh_activos} lo rechazaría.
     */
    private Dtos.SesionRespuesta emitir(Usuario usuario, UUID familiaId) {
        Instant ahora = Instant.now();
        refreshes.findByUsuarioIdAndFechaUsoIsNull(usuario.getId()).forEach(previo -> previo.marcarUso(ahora));
        refreshes.flush();
        String crudo = TokensSeguridad.generarCrudo();
        TokenRefresh token = new TokenRefresh();
        token.setUsuarioId(usuario.getId());
        token.setHashToken(TokensSeguridad.sha256Hex(crudo));
        token.setFechaExpiracion(ahora.plus(30, ChronoUnit.DAYS));
        token.setFamiliaId(familiaId);
        refreshes.save(token);
        return new Dtos.SesionRespuesta(
                emitirAcceso(usuario, ahora), crudo,
                propiedades.ttlAcceso().toSeconds(), Dtos.UsuarioRespuesta.de(usuario));
    }

    private String emitirAcceso(Usuario usuario, Instant ahora) {
        SecretKey clave = new SecretKeySpec(
                propiedades.secreto().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder codificador = NimbusJwtEncoder.withSecretKey(clave).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(usuario.getId().toString())
                .claim("roles", List.of(usuario.getRol().name()))
                .issuedAt(ahora)
                .expiresAt(ahora.plus(propiedades.ttlAcceso()))
                .build();
        return codificador.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
