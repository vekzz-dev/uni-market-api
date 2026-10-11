package com.unimarket.api.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * Jerarquía de roles {@code admin ⊃ moderador ⊃ miembro} (R3, SDD §8): habilita la
 * herencia de permisos en las expresiones de autorización ({@code hasRole},
 * {@code @PreAuthorize}) de los módulos de dominio.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionJerarquiaRoles {

    @Bean
    RoleHierarchy jerarquiaRoles() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_MODERADOR\nROLE_MODERADOR > ROLE_MIEMBRO");
    }
}
