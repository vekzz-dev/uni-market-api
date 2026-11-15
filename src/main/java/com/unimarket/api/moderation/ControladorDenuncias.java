package com.unimarket.api.moderation;

import com.unimarket.api.identity.LimitadorTasa;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Denuncia de un anuncio por un miembro (R19, issue #9).
 *
 * <p>Vive en {@code moderation} aunque la ruta cuelgue de {@code /anuncios}: el recurso es
 * una denuncia, y ponerla en {@code catalog} obligaría a catalog a depender de moderation
 * (ciclo). La dependencia permitida sigue siendo {@code moderation → catalog}.
 */
@RestController
@RequestMapping("/v1/anuncios/{anuncioId}/denuncias")
public class ControladorDenuncias {

    private final ServicioModeracion moderacion;
    private final LimitadorTasa tasa;

    public ControladorDenuncias(ServicioModeracion moderacion, LimitadorTasa tasa) {
        this.moderacion = moderacion;
        this.tasa = tasa;
    }

    /** Registra la denuncia: {@code 201}; duplicado {@code 409}; anuncio no visible {@code 404}; propio {@code 422}. */
    @PostMapping
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosModeracion.DenunciaPanel> denunciar(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID anuncioId,
            @Valid @RequestBody DtosModeracion.DenunciaRequest cuerpo,
            HttpServletRequest solicitud) {
        UUID denunciante = UUID.fromString(jwt.getSubject());
        tasa.exigir("denuncia", solicitud.getRemoteAddr(), denunciante.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                moderacion.denunciar(anuncioId, denunciante, cuerpo.motivo(), cuerpo.detalle()));
    }
}
