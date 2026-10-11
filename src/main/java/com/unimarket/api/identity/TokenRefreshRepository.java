package com.unimarket.api.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code tokens_refresh} (R12): lookup por hash del valor presentado, activos por
 * usuario y miembros de una familia de rotación para revocarla ante reuso.
 */
public interface TokenRefreshRepository extends JpaRepository<TokenRefresh, UUID> {

    /** Refresh por hash SHA-256 del valor crudo presentado (R12). */
    Optional<TokenRefresh> findByHashToken(String hashToken);

    /** Refresh sin consumir del usuario: a lo sumo uno tras cada rotación (R12). */
    List<TokenRefresh> findByUsuarioIdAndFechaUsoIsNull(UUID usuarioId);

    /** Miembros de una familia de rotación: el reuso de uno revoca todos (R12). */
    List<TokenRefresh> findByFamiliaId(UUID familiaId);
}
