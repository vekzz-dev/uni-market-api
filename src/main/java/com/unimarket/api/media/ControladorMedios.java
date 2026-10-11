package com.unimarket.api.media;

import com.unimarket.api.shared.error.NoEncontradoException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura pública de objetos (R8): sirve {@code GET /media/**} con el tipo del objeto.
 * Ruta permitida sin autenticación en la cadena de seguridad.
 */
@RestController
public class ControladorMedios {

    private final AlmacenObjetos almacen;

    public ControladorMedios(AlmacenObjetos almacen) {
        this.almacen = almacen;
    }

    /** Sirve el objeto por su ruta relativa; inexistente responde {@code 404}. */
    @GetMapping("/media/**")
    public ResponseEntity<byte[]> servir(HttpServletRequest solicitud) {
        String uri = solicitud.getRequestURI();
        String ruta = uri.startsWith(AlmacenObjetos.PREFIJO_PUBLICO)
                ? uri.substring(AlmacenObjetos.PREFIJO_PUBLICO.length())
                : "";
        AlmacenObjetos.Contenido contenido = almacen.leer(ruta)
                .orElseThrow(() -> new NoEncontradoException("El objeto no existe."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contenido.contentType()))
                .body(contenido.bytes());
    }
}
