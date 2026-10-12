package com.unimarket.api.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Categorías (openapi.yaml, tag Categorias): lectura pública ordenada; el CRUD de
 * moderador pertenece al módulo {@code admin} (SDD §4) y llega en su feature.
 */
@RestController
@RequestMapping("/v1/categorias")
public class ControladorCategorias {

    private final ServicioCatalogo catalogo;

    public ControladorCategorias(ServicioCatalogo catalogo) {
        this.catalogo = catalogo;
    }

    /** Listado público por {@code orden} (semilla idempotente del DBDD §4). */
    @GetMapping
    public ResponseEntity<DtosAnuncios.CategoriasRespuesta> listar() {
        return ResponseEntity.ok(catalogo.categorias());
    }
}
