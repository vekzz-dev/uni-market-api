package com.unimarket.api.messaging;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Conversaciones y mensajes (openapi.yaml, tags Conversaciones/Mensajes): todas las rutas
 * exigen sesión; la participación se verifica en el servicio (403/404, R10). Sin tiempo
 * real: la respuesta HTTP del envío es el contrato (PRD §2).
 */
@RestController
@RequestMapping("/v1")
public class ControladorConversaciones {

    private final ServicioConversaciones conversaciones;

    public ControladorConversaciones(ServicioConversaciones conversaciones) {
        this.conversaciones = conversaciones;
    }

    /** Abre o reutiliza: {@code 201} nueva, {@code 200} existente (R9). El header es opcional y de eco (D6). */
    @PostMapping("/conversaciones")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosMensajeria.ConversacionResumen> abrir(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String claveIdempotencia,
            @Valid @RequestBody DtosMensajeria.CrearConversacionRequest cuerpo) {
        ServicioConversaciones.Apertura apertura = conversaciones.abrir(idActual(jwt), cuerpo.anuncioId());
        return ResponseEntity.status(apertura.creada() ? HttpStatus.CREATED : HttpStatus.OK).body(apertura.conversacion());
    }

    /** Panel propio ordenado por actividad, con ultimoMensaje y noLeidos. */
    @GetMapping("/mis-conversaciones")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<RespuestaColeccion<DtosMensajeria.ConversacionItem>> misConversaciones(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(conversaciones.misConversaciones(idActual(jwt), pagina, limite));
    }

    /** Detalle: solo participantes (403 para el resto, R10). */
    @GetMapping("/conversaciones/{conversacionId}")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosMensajeria.ConversacionDetalle> detalle(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversacionId) {
        return ResponseEntity.ok(conversaciones.detalle(conversacionId, idActual(jwt)));
    }

    /** Historial con cursor hacia atrás; ningún GET muta {@code leido} (PRD §5). */
    @GetMapping("/conversaciones/{conversacionId}/mensajes")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<RespuestaColeccion<DtosMensajeria.MensajeRespuesta>> historial(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID conversacionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(conversaciones.historial(conversacionId, idActual(jwt), cursor, limite));
    }

    /** Envía un mensaje: texto no vacío (422), participantes activos (R10/R16). */
    @PostMapping("/conversaciones/{conversacionId}/mensajes")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosMensajeria.MensajeRespuesta> enviar(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID conversacionId,
            @Valid @RequestBody DtosMensajeria.EnviarMensajeRequest cuerpo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversaciones.enviar(conversacionId, idActual(jwt), cuerpo.texto()));
    }

    /** Marcado explícito e idempotente de lecturas (PRD §5). */
    @PostMapping("/conversaciones/{conversacionId}/lecturas")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Void> marcarLecturas(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversacionId) {
        conversaciones.marcarLecturas(conversacionId, idActual(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID idActual(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
