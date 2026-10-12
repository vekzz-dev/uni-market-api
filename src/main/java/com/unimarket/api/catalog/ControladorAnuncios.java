package com.unimarket.api.catalog;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.unimarket.api.shared.pagination.RespuestaColeccion;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Anuncios (openapi.yaml, tags Catálogo/Anuncios): catálogo y detalle son públicos;
 * crear, editar, visibilidad, vendido, baja y "mis anuncios" exigen sesión, con el
 * ownership verificado en el servicio y en el repositorio (R7).
 */
@RestController
@RequestMapping("/v1")
public class ControladorAnuncios {

    private final ServicioAnuncios anuncios;
    private final ServicioCatalogo catalogo;

    public ControladorAnuncios(ServicioAnuncios anuncios, ServicioCatalogo catalogo) {
        this.anuncios = anuncios;
        this.catalogo = catalogo;
    }

    /** Catálogo público con cursor compuesto según el orden (R4, D4). */
    @GetMapping("/anuncios")
    public ResponseEntity<RespuestaColeccion<DtosAnuncios.AnuncioPublico>> buscar(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer categoriaId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String orden) {
        return ResponseEntity.ok(catalogo.buscar(q, categoriaId, cursor, limite, orden));
    }

    /** Detalle público: no visible o inexistente → 404 indistinguible (R4). */
    @GetMapping("/anuncios/{anuncioId}")
    public ResponseEntity<DtosAnuncios.AnuncioPublico> detalle(@PathVariable UUID anuncioId) {
        return ResponseEntity.ok(catalogo.detalle(anuncioId));
    }

    /** Publica un anuncio: SIEMPRE nace {@code pendiente}, sin excepción de rol (R5). */
    @PostMapping(value = "/anuncios", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.AnuncioPanel> crear(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String nombre,
            @RequestParam BigDecimal precio,
            @RequestParam(required = false) String descripcion,
            @RequestParam(required = false) Integer categoriaId,
            @RequestParam(name = "imagenes", required = false) List<MultipartFile> imagenes) {
        DtosAnuncios.AnuncioPanel creado =
                anuncios.crear(idActual(jwt), nombre, precio, descripcion, categoriaId, imagenes);
        return ResponseEntity.created(URI.create("/v1/anuncios/" + creado.id())).body(creado);
    }

    /** Edita contenido: si estaba aprobado o rechazado vuelve a la cola (R6). */
    @PatchMapping("/anuncios/{anuncioId}/contenido")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.AnuncioPanel> editar(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID anuncioId,
            @Valid @RequestBody DtosAnuncios.ActualizarRequest cambios) {
        return ResponseEntity.ok(anuncios.editar(idActual(jwt), anuncioId, cambios));
    }

    /** Reemplaza TODAS las imágenes del anuncio (N6): multipart 1..5, resetea revisión (R6). */
    @PutMapping(value = "/anuncios/{anuncioId}/imagenes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.AnuncioPanel> reemplazarImagenes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID anuncioId,
            @RequestParam(name = "imagenes", required = false) List<MultipartFile> imagenes) {
        return ResponseEntity.ok(anuncios.reemplazarImagenes(idActual(jwt), anuncioId, imagenes));
    }

    /** Alterna disponible↔inactivo: solo sobre anuncio aprobado (PRD §5). */
    @PostMapping("/anuncios/{anuncioId}/visibilidad")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.AnuncioPanel> visibilidad(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID anuncioId,
            @Valid @RequestBody DtosAnuncios.VisibilidadRequest cuerpo) {
        return ResponseEntity.ok(anuncios.alternarVisibilidad(idActual(jwt), anuncioId, cuerpo.visible()));
    }

    /** Marca vendido desde disponible|inactivo. */
    @PostMapping("/anuncios/{anuncioId}/vendido")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.AnuncioPanel> vendido(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID anuncioId) {
        return ResponseEntity.ok(anuncios.marcarVendido(idActual(jwt), anuncioId));
    }

    /** Baja lógica: conserva conversaciones y objetos (R1). */
    @DeleteMapping("/anuncios/{anuncioId}")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<Void> darDeBaja(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID anuncioId) {
        anuncios.darDeBaja(idActual(jwt), anuncioId);
        return ResponseEntity.noContent().build();
    }

    /** Panel del vendedor con badge de revisión y motivo de rechazo (R7). */
    @GetMapping("/mis-anuncios")
    @PreAuthorize("hasAnyRole('MIEMBRO','MODERADOR','ADMIN')")
    public ResponseEntity<RespuestaColeccion<DtosAnuncios.AnuncioPanel>> misAnuncios(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(catalogo.misAnuncios(idActual(jwt), pagina, limite));
    }

    private static UUID idActual(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
