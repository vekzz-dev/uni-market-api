package com.unimarket.api.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code tokens_restablecimiento_contrasena} (R11): lookup por hash del valor
 * presentado y activos por usuario para invalidar previos al reenviar.
 */
public interface TokenRestablecimientoRepository extends JpaRepository<TokenRestablecimientoContrasena, UUID> {

    /** Token por hash SHA-256 del valor crudo presentado (R11). */
    Optional<TokenRestablecimientoContrasena> findByHashToken(String hashToken);

    /** Tokens sin consumir del usuario: a lo sumo uno tras cada emisión (R11). */
    List<TokenRestablecimientoContrasena> findByUsuarioIdAndFechaUsoIsNull(UUID usuarioId);
}
