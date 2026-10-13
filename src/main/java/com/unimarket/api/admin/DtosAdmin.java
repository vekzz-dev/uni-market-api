package com.unimarket.api.admin;

import jakarta.validation.constraints.NotNull;

/**
 * Contratos del wire de admin (openapi.yaml, tag Administracion): el rol viaja como
 * String para que un valor fuera del dominio responda {@code 422} (excepción declarada
 * de R14) en vez del {@code 400} de deserialización.
 */
public final class DtosAdmin {

    private DtosAdmin() {
    }

    /** R14: destino fuera del dominio o igual al actual → 422; el propio rol → 403 en el servicio. */
    public record ActualizarRolRequest(String rol) {
    }

    /** R15: habilitación reversible; jamás borra usuarios ni escribe fecha de baja. */
    public record HabilitacionRequest(@NotNull Boolean habilitado) {
    }

    /** Alta/edición de categoría (moderador): nombre obligatorio y único; icono ≤ 10 (422). */
    public record CategoriaRequest(String nombre, String descripcion, String icono) {
    }
}
