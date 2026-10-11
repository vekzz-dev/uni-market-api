package com.unimarket.api.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code tokens_verificacion_email} (R11): lookup por hash del valor presentado
 * y activos por usuario para invalidar previos al reenviar.
 */
public interface TokenVerificacionEmailRepository extends JpaRepository<TokenVerificacionEmail, UUID> {

    /** Token por hash SHA-256 del valor crudo presentado (R11). */
    Optional<TokenVerificacionEmail> findByHashToken(String hashToken);

    /** Tokens sin consumir del usuario: a lo sumo uno tras cada emisión (R11). */
    List<TokenVerificacionEmail> findByUsuarioIdAndFechaUsoIsNull(UUID usuarioId);
}
