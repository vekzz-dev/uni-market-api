package com.unimarket.api.admin;

import com.unimarket.api.identity.ServicioAdministracionUsuarios;
import com.unimarket.api.identity.UsuarioAdminRespuesta;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import com.unimarket.api.shared.security.Rol;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Gestión de usuarios (openapi.yaml, tag Administracion): solo {@code admin}. La lógica
 * vive en {@link ServicioAdministracionUsuarios} (identity, servicio expuesto — misma
 * frontera de módulos que moderation/catalog). El panel jamás borra usuarios ni escribe
 * la fecha de baja (R15).
 */
@RestController
@RequestMapping("/v1/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')")
public class ControladorAdminUsuarios {

    private final ServicioAdministracionUsuarios administracion;

    public ControladorAdminUsuarios(ServicioAdministracionUsuarios administracion) {
        this.administracion = administracion;
    }

    /** Listado filtrable por rol: solo cuentas activas (R15), offset + total. */
    @GetMapping
    public ResponseEntity<RespuestaColeccion<UsuarioAdminRespuesta>> listar(
            @RequestParam(required = false) Rol rol,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(administracion.listar(rol, pagina, limite));
    }

    /** Cambio de rol (R14): 403 el propio, 422 fuera de dominio o igual al actual. */
    @PatchMapping("/{usuarioId}/rol")
    public ResponseEntity<UsuarioAdminRespuesta> cambiarRol(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID usuarioId,
            @Valid @RequestBody DtosAdmin.ActualizarRolRequest cuerpo) {
        return ResponseEntity.ok(
                administracion.cambiarRol(UUID.fromString(jwt.getSubject()), usuarioId, cuerpo.rol()));
    }

    /** Habilitación reversible (R15): corta la sesión vigente con 401 indistinguible. */
    @PatchMapping("/{usuarioId}/habilitacion")
    public ResponseEntity<UsuarioAdminRespuesta> cambiarHabilitacion(
            @PathVariable UUID usuarioId,
            @Valid @RequestBody DtosAdmin.HabilitacionRequest cuerpo) {
        return ResponseEntity.ok(administracion.cambiarHabilitacion(usuarioId, cuerpo.habilitado()));
    }
}
