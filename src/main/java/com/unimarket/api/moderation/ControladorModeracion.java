package com.unimarket.api.moderation;

import com.unimarket.api.catalog.EstadoRevision;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Moderación (openapi.yaml, tag Moderacion): cola FIFO por estado de revisión con el
 * contador de denuncias abiertas, decisiones explícitas y gestión de denuncias (R5, R19).
 *
 * <p>La lógica de revisión vive en {@code catalog/ServicioRevision} —único escritor de las
 * columnas de moderación— y este módulo la envuelve para añadir lo que es suyo: las
 * denuncias.
 */
@RestController
@RequestMapping("/v1/moderacion")
public class ControladorModeracion {

    private final ServicioModeracion moderacion;

    public ControladorModeracion(ServicioModeracion moderacion) {
        this.moderacion = moderacion;
    }

    /** Cola FIFO: default {@code pendiente}, orden {@code fechaCreacion ASC} (PRD §5). */
    @GetMapping("/anuncios")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<RespuestaColeccion<DtosModeracion.PanelModeracion>> cola(
            @RequestParam(required = false) EstadoRevision estadoRevision,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(moderacion.cola(estadoRevision, pagina, limite));
    }

    /** Registra una decisión de revisión con su auditoría (R5) y cierra las denuncias si se actuó. */
    @PostMapping("/anuncios/{anuncioId}/decisiones")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<DtosModeracion.PanelModeracion> decidir(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID anuncioId,
            @Valid @RequestBody DtosModeracion.DecisionRequest cuerpo) {
        return ResponseEntity.ok(moderacion.decidir(
                anuncioId, UUID.fromString(jwt.getSubject()), cuerpo.decision(), cuerpo.motivoRechazo()));
    }

    /** Cola de denuncias por estado (default {@code abierta}), FIFO con desempate por id. */
    @GetMapping("/denuncias")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<RespuestaColeccion<DtosModeracion.DenunciaPanel>> denuncias(
            @RequestParam(required = false) EstadoDenuncia estado,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(moderacion.denuncias(estado, pagina, limite));
    }

    /** Resuelve una denuncia: {@code revisada} (se actuó) o {@code descartada} (no procedía). */
    @PostMapping("/denuncias/{denunciaId}/resolucion")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<DtosModeracion.DenunciaPanel> resolver(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID denunciaId,
            @Valid @RequestBody DtosModeracion.ResolucionRequest cuerpo) {
        return ResponseEntity.status(HttpStatus.OK).body(
                moderacion.resolver(denunciaId, cuerpo.estado(), UUID.fromString(jwt.getSubject())));
    }
}
