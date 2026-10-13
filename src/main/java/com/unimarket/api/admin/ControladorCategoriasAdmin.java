package com.unimarket.api.admin;

import com.unimarket.api.catalog.DtosAnuncios;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD de categorías (openapi.yaml, tag Categorias): escritura solo para {@code moderador}
 * o superior; la lectura pública (`GET /v1/categorias`) vive en el módulo catalog sobre
 * la misma ruta con distinto método.
 */
@RestController
@RequestMapping("/v1/categorias")
public class ControladorCategoriasAdmin {

    private final ServicioAdminCategorias categorias;

    public ControladorCategoriasAdmin(ServicioAdminCategorias categorias) {
        this.categorias = categorias;
    }

    /** Crea al final del orden (`MAX+1`); nombre duplicado → 409; límites → 422. */
    @PostMapping
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.CategoriaRespuesta> crear(@Valid @RequestBody DtosAdmin.CategoriaRequest cuerpo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categorias.crear(cuerpo.nombre(), cuerpo.descripcion(), cuerpo.icono()));
    }

    /** Actualiza nombre/descripción/icono conservando el orden. */
    @PatchMapping("/{categoriaId}")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<DtosAnuncios.CategoriaRespuesta> actualizar(
            @PathVariable Integer categoriaId, @Valid @RequestBody DtosAdmin.CategoriaRequest cuerpo) {
        return ResponseEntity.ok(categorias.actualizar(categoriaId, cuerpo.nombre(), cuerpo.descripcion(), cuerpo.icono()));
    }

    /** Borrado físico: los anuncios sobreviven con categoría nula (SET NULL). */
    @DeleteMapping("/{categoriaId}")
    @PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")
    public ResponseEntity<Void> eliminar(@PathVariable Integer categoriaId) {
        categorias.eliminar(categoriaId);
        return ResponseEntity.noContent().build();
    }
}
