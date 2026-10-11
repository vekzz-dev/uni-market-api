package com.unimarket.api.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de autoridad por request (D3): devuelve el estado vigente del usuario, o vacío
 * si no existe. Lo implementa el módulo {@code identity}; {@code shared} no depende de
 * ningún módulo de dominio (frontera ArchUnit, SDD §4).
 */
public interface EstadoUsuarioProvider {

    Optional<EstadoUsuario> porId(UUID id);
}
