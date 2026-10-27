package com.unimarket.api.catalog;

import com.unimarket.api.shared.error.EstructuraInvalidaException;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.pagination.CursorCodec;
import com.unimarket.api.shared.pagination.PaginacionCursor;
import com.unimarket.api.shared.pagination.PaginacionOffset;
import com.unimarket.api.shared.pagination.RegistroCursor;
import com.unimarket.api.shared.pagination.RegistroCursorPrecio;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Lecturas del catálogo (R4, O3): visibilidad como predicado de repositorio, paginación
 * por cursor compuesto (D4) según el criterio de orden, y {@code 404} indistinguible para
 * anuncios no visibles o inexistentes.
 */
@Service
public class ServicioCatalogo {

    static final int LIMITE_DEFECTO = 20;
    static final int LIMITE_MAXIMO = 100;

    private final AnuncioRepository anuncios;
    private final JdbcTemplate jdbc;
    private final CursorCodec cursorCodec;
    private final MapeadorAnuncios mapeador;

    public ServicioCatalogo(
            AnuncioRepository anuncios, JdbcTemplate jdbc, CursorCodec cursorCodec, MapeadorAnuncios mapeador) {
        this.anuncios = anuncios;
        this.jdbc = jdbc;
        this.cursorCodec = cursorCodec;
        this.mapeador = mapeador;
    }

    /** Catálogo público: solo anuncios visibles, orden por fecha (desc) o precio (asc). */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosAnuncios.AnuncioResumen> buscar(
            String q, Integer categoriaId, String cursor, Integer limite, String orden) {
        int tamano = validarLimite(limite);
        boolean porPrecio = "precio".equals(orden);
        if (orden != null && !orden.isBlank() && !porPrecio && !"fechaCreacion".equals(orden)) {
            throw new EstructuraInvalidaException("El parámetro 'orden' solo admite fechaCreacion o precio.");
        }

        Specification<Anuncio> filtro = EspecificacionesAnuncio.visible();
        if (categoriaId != null) {
            filtro = filtro.and(EspecificacionesAnuncio.deCategoria(categoriaId));
        }
        if (q != null && !q.isBlank()) {
            if (q.length() > 200) {
                throw new EstructuraInvalidaException("El parámetro 'q' admite hasta 200 caracteres.");
            }
            filtro = filtro.and(EspecificacionesAnuncio.busqueda(q.strip()));
        }

        Sort sort = porPrecio
                ? Sort.by(Sort.Order.asc("precio"), Sort.Order.asc("id"))
                : Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"));

        if (cursor != null && !cursor.isBlank()) {
            if (porPrecio) {
                RegistroCursorPrecio estado = cursorCodec.decodificarPrecio(cursor);
                filtro = filtro.and(EspecificacionesAnuncio.despuesDelCursorPrecio(estado.precio(), estado.id()));
            } else {
                RegistroCursor estado = cursorCodec.decodificar(cursor);
                filtro = filtro.and(EspecificacionesAnuncio.despuesDelCursor(estado.fechaCreacion(), estado.id()));
            }
        }

        // Se pide un elemento extra: su presencia indica que hay página siguiente.
        Page<Anuncio> pagina = anuncios.findAll(filtro, PageRequest.of(0, tamano + 1, sort));
        List<Anuncio> filas = pagina.getContent();
        boolean haySiguiente = filas.size() > tamano;
        List<Anuncio> visibles = haySiguiente ? filas.subList(0, tamano) : filas;

        String cursorSiguiente = null;
        if (haySiguiente && !visibles.isEmpty()) {
            Anuncio ultimo = visibles.get(visibles.size() - 1);
            cursorSiguiente = porPrecio
                    ? cursorCodec.codificarPrecio(ultimo.getPrecio(), ultimo.getId())
                    : cursorCodec.codificar(new RegistroCursor(ultimo.getFechaCreacion(), ultimo.getId()));
        }

        return new RespuestaColeccion<>(
                mapeador.resumenes(visibles),
                new PaginacionCursor(cursorSiguiente));
    }

    /** Detalle público: no visible o inexistente → 404 indistinguible (R4). */
    @Transactional(readOnly = true)
    public DtosAnuncios.AnuncioDetalle detalle(UUID anuncioId) {
        Anuncio anuncio = anuncios
                .findOne(EspecificacionesAnuncio.visible().and((raiz, consulta, cb) -> cb.equal(raiz.get("id"), anuncioId)))
                .orElseThrow(() -> new NoEncontradoException("El anuncio no existe o no es visible."));
        return mapeador.detalle(anuncio);
    }

    /**
     * Detalle SIN filtro de visibilidad, para el historial de mensajería (R16): una
     * conversación sigue legible aunque el anuncio después sea {@code vendido}, {@code inactivo}
     * o {@code eliminado} (el borrado es lógico, la fila siempre existe). Solo consumo interno
     * entre módulos — jamás se expone por una ruta pública.
     */
    @Transactional(readOnly = true)
    public DtosAnuncios.AnuncioDetalle detalleSinVisibilidad(UUID anuncioId) {
        return anuncios.findById(anuncioId)
                .map(mapeador::detalle)
                .orElseThrow(() -> new NoEncontradoException("El anuncio no existe."));
    }

    /**
     * Anuncio propio del panel (issue #3): el vendedor lo recupera aunque esté pendiente o
     * rechazado, algo que el detalle público oculta por R4. Ajeno e inexistente responden
     * el MISMO {@code 404}: el guard de ownership se aplica en el predicado (R7).
     */
    @Transactional(readOnly = true)
    public DtosAnuncios.AnuncioPanel panelPropio(UUID vendedorId, UUID anuncioId) {
        Anuncio anuncio = anuncios
                .findOne(EspecificacionesAnuncio.deVendedor(vendedorId)
                        .and((raiz, consulta, cb) -> cb.equal(raiz.get("id"), anuncioId)))
                .orElseThrow(() -> new NoEncontradoException("El anuncio no existe."));
        return mapeador.panel(anuncio);
    }

    /**
     * Panel del vendedor: ownership como predicado (R7) + offset con total. El filtro por
     * estado de revisión es aditivo y el total lo refleja (issue #3).
     */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosAnuncios.AnuncioPanel> misAnuncios(
            UUID vendedorId, String estadoRevision, Integer pagina, Integer limite) {
        int tamano = validarLimite(limite);
        int indice = pagina == null || pagina < 0 ? 0 : pagina;
        Specification<Anuncio> filtro = EspecificacionesAnuncio.deVendedor(vendedorId);
        if (estadoRevision != null && !estadoRevision.isBlank()) {
            filtro = filtro.and(validarFiltro(estadoRevision).especificacion());
        }
        Page<Anuncio> resultado = anuncios.findAll(
                filtro,
                PageRequest.of(indice, tamano, Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"))));
        return new RespuestaColeccion<>(
                mapeador.paneles(resultado.getContent()),
                new PaginacionOffset(indice, tamano, resultado.getTotalElements(), resultado.getTotalPages()));
    }

    /** Lectura pública de categorías ordenadas (semilla V2 del DBDD §4). */
    @Transactional(readOnly = true)
    public DtosAnuncios.CategoriasRespuesta categorias() {
        List<DtosAnuncios.CategoriaRespuesta> datos = jdbc.query(
                "SELECT id, nombre, descripcion, icono, orden FROM categorias ORDER BY orden",
                (rs, fila) -> new DtosAnuncios.CategoriaRespuesta(
                        rs.getInt("id"),
                        rs.getString("nombre"),
                        rs.getString("descripcion"),
                        rs.getString("icono"),
                        rs.getInt("orden")));
        return new DtosAnuncios.CategoriasRespuesta(datos);
    }

    /**
     * El valor del filtro llega como texto y se valida aquí: un enum de Spring respondería
     * un error de conversión genérico y el contrato exige un {@code 400} con mensaje claro.
     */
    private static FiltroPanel validarFiltro(String valor) {
        try {
            return FiltroPanel.valueOf(valor.strip());
        } catch (IllegalArgumentException desconocido) {
            throw new EstructuraInvalidaException(
                    "El parámetro 'estadoRevision' solo admite pendiente, aprobado, rechazado o retraido.");
        }
    }

    private static int validarLimite(Integer limite) {
        if (limite == null) {
            return LIMITE_DEFECTO;
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new EstructuraInvalidaException("El parámetro 'limite' debe estar entre 1 y 100.");
        }
        return limite;
    }
}
