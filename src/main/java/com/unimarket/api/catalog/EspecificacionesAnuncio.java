package com.unimarket.api.catalog;

import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Predicados de negocio como {@code Specification} (decisión D5): visibilidad del catálogo
 * (R4), ownership (R7) y cursores compuestos del paginado (D4). Reutilizables y testeados:
 * el filtro vive aquí, no como parámetro opcional del servicio.
 */
public final class EspecificacionesAnuncio {

    /** Carácter de escape para {@code LIKE}: el usuario busca literal, no comodines. */
    static final char ESCAPE = '\\';

    private EspecificacionesAnuncio() {
    }

    /** Catálogo público: solo {@code disponible} + {@code aprobado} (R4). */
    public static Specification<Anuncio> visible() {
        return (raiz, consulta, cb) -> cb.and(
                cb.equal(raiz.get("estado"), EstadoAnuncio.disponible),
                cb.equal(raiz.get("estadoRevision"), EstadoRevision.aprobado));
    }

    /** Ownership (R7): "mis anuncios" jamás devuelve filas ajenas. */
    public static Specification<Anuncio> deVendedor(UUID vendedorId) {
        return (raiz, consulta, cb) -> cb.equal(raiz.get("vendedorId"), vendedorId);
    }

    /** Filtro por categoría del catálogo. */
    public static Specification<Anuncio> deCategoria(Integer categoriaId) {
        return (raiz, consulta, cb) -> cb.equal(raiz.get("categoriaId"), categoriaId);
    }

    /**
     * Búsqueda de texto en nombre y descripción con escape de comodines {@code % _ \}:
     * un {@code %} escrito por el usuario es un carácter literal, no un comodín.
     */
    public static Specification<Anuncio> busqueda(String texto) {
        String patron = "%" + escapar(texto.toLowerCase()) + "%";
        return (raiz, consulta, cb) -> cb.or(
                cb.like(cb.lower(raiz.get("nombre")), patron, ESCAPE),
                cb.like(cb.lower(raiz.get("descripcion")), patron, ESCAPE));
    }

    /** Cursor por defecto (D4): filas con tupla {@code (fechaCreacion, id)} estrictamente menor. */
    public static Specification<Anuncio> despuesDelCursor(Instant fechaCreacion, UUID id) {
        return (raiz, consulta, cb) -> cb.or(
                cb.lessThan(raiz.get("fechaCreacion"), fechaCreacion),
                cb.and(
                        cb.equal(raiz.get("fechaCreacion"), fechaCreacion),
                        cb.lessThan(raiz.get("id"), id)));
    }

    /** Cursor con {@code orden=precio}: tupla {@code (precio, id)} estrictamente mayor. */
    public static Specification<Anuncio> despuesDelCursorPrecio(BigDecimal precio, UUID id) {
        return (raiz, consulta, cb) -> cb.or(
                cb.greaterThan(raiz.get("precio"), precio),
                cb.and(
                        cb.equal(raiz.get("precio"), precio),
                        cb.greaterThan(raiz.get("id"), id)));
    }

    /** Escapa el texto del usuario para que {@code %}, {@code _} y {@code \} sean literales. */
    static String escapar(String texto) {
        return texto
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
