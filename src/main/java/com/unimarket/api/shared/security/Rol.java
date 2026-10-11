package com.unimarket.api.shared.security;

/**
 * Roles del dominio cerrado (R3): cualquier otro valor viola el CHECK de la BD. El rol
 * viaja como claim {@code roles} del JWT, pero la autoridad de autorización es la fila
 * del usuario resuelta en cada request (D3, R14).
 */
public enum Rol {
    miembro,
    moderador,
    admin
}
