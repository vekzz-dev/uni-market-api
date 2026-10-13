package com.unimarket.api.admin;

import com.unimarket.api.catalog.DtosAnuncios;
import com.unimarket.api.shared.error.ConflictoException;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * CRUD de categorías (moderador, SDD §4): {@code orden = MAX(orden)+1} para que las nuevas
 * nunca queden en 0, nombre único → {@code 409}, límites de nombre/icono → {@code 422} y
 * borrado físico donde los anuncios sobreviven con {@code categoriaId} nulo
 * ({@code ON DELETE SET NULL}, DBDD §5). Se usa JDBC por consistencia con la lectura
 * pública de catalog (no hay entidad de categoría en el dominio JPA).
 */
@Service
public class ServicioAdminCategorias {

    static final int NOMBRE_MAXIMO = 100;
    static final int ICONO_MAXIMO = 10;

    private final JdbcTemplate jdbc;

    public ServicioAdminCategorias(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Crea una categoría al final del orden (nunca descartada en 0). */
    public DtosAnuncios.CategoriaRespuesta crear(String nombre, String descripcion, String icono) {
        String nombreLimpio = validar(nombre, icono);
        exigirNombreLibre(nombreLimpio, null);
        int orden = jdbc.queryForObject("SELECT coalesce(max(orden), 0) + 1 FROM categorias", Integer.class);
        try {
            Integer id = jdbc.queryForObject(
                    "INSERT INTO categorias (nombre, descripcion, icono, orden) VALUES (?, ?, ?, ?) RETURNING id",
                    Integer.class, nombreLimpio, vacioANulo(descripcion), vacioANulo(icono), orden);
            return new DtosAnuncios.CategoriaRespuesta(id, nombreLimpio, vacioANulo(descripcion), vacioANulo(icono), orden);
        } catch (DataIntegrityViolationException carrera) {
            // Carrera contra categorias_nombre_key: mismo 409 que el pre-chequeo.
            throw new ConflictoException("Ya existe una categoría con ese nombre.");
        }
    }

    /** Actualiza nombre/descripción/icono; conserva el orden actual. */
    public DtosAnuncios.CategoriaRespuesta actualizar(Integer categoriaId, String nombre, String descripcion, String icono) {
        Integer orden = jdbc.query(
                "SELECT orden FROM categorias WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, categoriaId);
        if (orden == null) {
            throw new NoEncontradoException("La categoría no existe.");
        }
        String nombreLimpio = validar(nombre, icono);
        exigirNombreLibre(nombreLimpio, categoriaId);
        try {
            jdbc.update("UPDATE categorias SET nombre = ?, descripcion = ?, icono = ? WHERE id = ?",
                    nombreLimpio, vacioANulo(descripcion), vacioANulo(icono), categoriaId);
        } catch (DataIntegrityViolationException carrera) {
            throw new ConflictoException("Ya existe una categoría con ese nombre.");
        }
        return new DtosAnuncios.CategoriaRespuesta(categoriaId, nombreLimpio, vacioANulo(descripcion), vacioANulo(icono), orden);
    }

    /** Borrado físico: los anuncios quedan con {@code categoriaId} nulo y siguen visibles. */
    public void eliminar(Integer categoriaId) {
        int filas = jdbc.update("DELETE FROM categorias WHERE id = ?", categoriaId);
        if (filas == 0) {
            throw new NoEncontradoException("La categoría no existe.");
        }
    }

    private void exigirNombreLibre(String nombre, Integer exceptoId) {
        long existentes = exceptoId == null
                ? jdbc.queryForObject("SELECT count(*) FROM categorias WHERE nombre = ?", Long.class, nombre)
                : jdbc.queryForObject("SELECT count(*) FROM categorias WHERE nombre = ? AND id <> ?", Long.class, nombre, exceptoId);
        if (existentes > 0) {
            throw new ConflictoException("Ya existe una categoría con ese nombre.");
        }
    }

    private static String validar(String nombre, String icono) {
        if (nombre == null || nombre.isBlank()) {
            throw new ReglaDeNegocioException("El nombre de la categoría es obligatorio.");
        }
        String limpio = nombre.strip();
        if (limpio.length() > NOMBRE_MAXIMO) {
            throw new ReglaDeNegocioException("El nombre admite hasta " + NOMBRE_MAXIMO + " caracteres.");
        }
        if (icono != null && icono.length() > ICONO_MAXIMO) {
            throw new ReglaDeNegocioException("El icono admite hasta " + ICONO_MAXIMO + " caracteres.");
        }
        return limpio;
    }

    private static String vacioANulo(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.strip();
    }
}
