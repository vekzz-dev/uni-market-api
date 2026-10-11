package com.unimarket.api.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Cuenta propia (openapi.yaml, tag Usuarios): registro público, perfil, avatar, cambio de
 * clave y baja. El registro es la única ruta pública; el resto exige cualquier rol
 * autenticado (la jerarquía vive en {@code shared}, SDD §8).
 */
@RestController
@RequestMapping("/v1/usuarios")
public class ControladorUsuarios {

    private final ServicioRegistro registro;
    private final ServicioRecuperacion recuperacion;
    private final ServicioPerfil perfil;
    private final LimitadorTasa tasa;

    public ControladorUsuarios(
            ServicioRegistro registro,
            ServicioRecuperacion recuperacion,
            ServicioPerfil perfil,
            LimitadorTasa tasa) {
        this.registro = registro;
        this.recuperacion = recuperacion;
        this.perfil = perfil;
        this.tasa = tasa;
    }

    /** Registro público: crea rol {@code miembro} sin emitir sesión (R3). */
    @PostMapping
    public ResponseEntity<Dtos.UsuarioRespuesta> registrar(
            @Valid @RequestBody Dtos.RegistroRequest cuerpo, HttpServletRequest solicitud) {
        tasa.exigir("registro", solicitud.getRemoteAddr(), cuerpo.email());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(registro.registrar(cuerpo.email(), cuerpo.nombre(), cuerpo.contrasena()));
    }

    /** Perfil del usuario autenticado (resuelto del {@code sub} del JWT, R1). */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Dtos.UsuarioRespuesta> leer(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(perfil.leer(idActual(jwt)));
    }

    /** Actualiza el perfil: solo el nombre es editable (R2). */
    @PatchMapping("/me")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Dtos.UsuarioRespuesta> actualizar(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Dtos.ActualizarPerfilRequest cuerpo) {
        return ResponseEntity.ok(perfil.actualizar(idActual(jwt), cuerpo.nombre()));
    }

    /** Baja soft-delete que corta la sesión (R1/R15). */
    @DeleteMapping("/me")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Void> darDeBaja(@AuthenticationPrincipal Jwt jwt) {
        perfil.darDeBaja(idActual(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Sube o reemplaza el avatar: 2 MB, jpeg/png/gif/webp (R8). */
    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Dtos.UsuarioRespuesta> subirAvatar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "archivo", required = false) MultipartFile archivo) {
        return ResponseEntity.ok(perfil.subirAvatar(idActual(jwt), archivo));
    }

    /** Elimina el avatar del storage y limpia {@code urlAvatar} (R8). */
    @DeleteMapping("/me/avatar")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Void> eliminarAvatar(@AuthenticationPrincipal Jwt jwt) {
        perfil.eliminarAvatar(idActual(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Cambio autenticado con verificación de la clave actual (R11). */
    @PostMapping("/me/contrasena")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Void> cambiarContrasena(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Dtos.CambiarContrasenaRequest cuerpo) {
        recuperacion.cambiarContrasena(idActual(jwt), cuerpo.contrasenaActual(), cuerpo.nuevaContrasena());
        return ResponseEntity.noContent().build();
    }

    private static UUID idActual(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
