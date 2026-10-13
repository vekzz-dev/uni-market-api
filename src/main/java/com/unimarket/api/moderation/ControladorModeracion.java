package com.unimarket.api.moderation;

import com.unimarket.api.catalog.DtosAnuncios;
import com.unimarket.api.catalog.EstadoRevision;
import com.unimarket.api.catalog.ServicioRevision;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import jakarta.validation.Valid;
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
 * Moderación (openapi.yaml, tag Moderacion): cola FIFO por estado de revisión y decisiones
 * explícitas. Exige rol {@code moderador} o superior; la lógica y las invariantes viven en
 * {@link ServicioRevision} (catalog), el único escritor de las columnas de moderación (R5).
 */
@RestController
@RequestMapping("/v1/moderacion")
public class ControladorModeracion {

    private final ServicioRevision revision;

    public ControladorModeracion(ServicioRevision revision) {
        this.revision = revision;
    }

    /** Cola FIFO: default {@code pendiente}, orden {@code fechaCreacion ASC} (PRD §5). */
    @GetMapping("/anuncios")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<RespuestaColeccion<DtosAnuncios.AnuncioPanel>> cola(
            @RequestParam(required = false) EstadoRevision estadoRevision,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(revision.cola(estadoRevision, pagina, limite));
    }

    /** Registra una decisión de revisión con su auditoría (R5). */
    @PostMapping("/anuncios/{anuncioId}/decisiones")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.AnuncioPanel> decidir(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID anuncioId,
            @Valid @RequestBody DtosModeracion.DecisionRequest cuerpo) {
        return ResponseEntity.ok(
                revision.decidir(anuncioId, UUID.fromString(jwt.getSubject()), cuerpo.decision(), cuerpo.motivoRechazo()));
    }
}
