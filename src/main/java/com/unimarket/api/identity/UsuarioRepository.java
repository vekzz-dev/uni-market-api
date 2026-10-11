package com.unimarket.api.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code usuarios} (R1/R2): la búsqueda por email ignora el caso y excluye las
 * cuentas dadas de baja, en espejo al índice parcial {@code ux_usuarios_email_activo}.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    /** Cuenta activa por email, comparando sin distinguir mayúsculas (R2). */
    Optional<Usuario> findByEmailIgnoreCaseAndFechaEliminacionIsNull(String email);

    /** Existe cuenta activa con ese email en cualquier caso (R2 → 409 en registro). */
    boolean existsByEmailIgnoreCaseAndFechaEliminacionIsNull(String email);
}
