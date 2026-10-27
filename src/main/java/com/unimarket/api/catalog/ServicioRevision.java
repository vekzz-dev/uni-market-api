package com.unimarket.api.catalog;

import com.unimarket.api.shared.error.EstructuraInvalidaException;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.pagination.PaginacionOffset;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Servicio de revisión (R5): ÚNICO escritor de {@code estadoRevision}, {@code revisadoPor},
 * {@code fechaRevision} y {@code motivoRechazo}. El módulo {@code moderation} lo consume
 * como servicio de aplicación expuesto (SDD §4) — nadie más toca estas columnas, y ningún
 * rol puede decidir sobre su PROPIO anuncio (403, incluso admin).
 *
 * <p>Máquina de estados: {@code aprobar}/{@code rechazar} exigen {@code pendiente};
 * {@code retraer} exige {@code aprobado} (revierte una aprobación previa). Solo se
 * conserva la última revisión (PRD §7).
 */
@Service
public class ServicioRevision {

    static final int LIMITE_DEFECTO = 20;
    static final int LIMITE_MAXIMO = 100;

    private final AnuncioRepository anuncios;
    private final MapeadorAnuncios mapeador;

    public ServicioRevision(AnuncioRepository anuncios, MapeadorAnuncios mapeador) {
        this.anuncios = anuncios;
        this.mapeador = mapeador;
    }

    /**
     * Registra la decisión del moderador con su auditoría completa.
     *
     * @throws NoEncontradoException  si el anuncio no existe (404)
     * @throws ProhibidoException     si el revisor es el propio vendedor (403, R5)
     * @throws ReglaDeNegocioException si el estado de origen o el motivo no son válidos (422)
     */
    @Transactional
    public DtosAnuncios.AnuncioPanel decidir(UUID anuncioId, UUID revisorId, Decision decision, String motivoRechazo) {
        Anuncio anuncio = anuncios.findById(anuncioId)
                .orElseThrow(() -> new NoEncontradoException("El anuncio no existe."));
        if (anuncio.getVendedorId().equals(revisorId)) {
            throw new ProhibidoException("Nadie puede aprobar, rechazar ni retraer su propio anuncio.");
        }

        switch (decision) {
            case aprobar -> {
                exigirEstado(anuncio, EstadoRevision.pendiente, "Solo un anuncio pendiente puede aprobarse.");
                anuncio.setEstadoRevision(EstadoRevision.aprobado);
                anuncio.setMotivoRechazo(null);
                anuncio.setOrigenRechazo(null);
            }
            case rechazar -> {
                exigirEstado(anuncio, EstadoRevision.pendiente, "Solo un anuncio pendiente puede rechazarse.");
                anuncio.setEstadoRevision(EstadoRevision.rechazado);
                anuncio.setMotivoRechazo(exigirMotivo(motivoRechazo));
                anuncio.setOrigenRechazo(OrigenRechazo.rechazo);
            }
            case retraer -> {
                exigirEstado(anuncio, EstadoRevision.aprobado, "Solo un anuncio aprobado puede retraerse.");
                anuncio.setEstadoRevision(EstadoRevision.rechazado);
                anuncio.setMotivoRechazo(exigirMotivo(motivoRechazo));
                anuncio.setOrigenRechazo(OrigenRechazo.retraccion);
            }
        }
        anuncio.setRevisadoPor(revisorId);
        anuncio.setFechaRevision(Instant.now());
        return mapeador.panel(anuncios.saveAndFlush(anuncio));
    }

    /** Cola por estado de revisión: FIFO estable por {@code (fechaCreacion, id)} + offset. */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosAnuncios.AnuncioPanel> cola(EstadoRevision estadoRevision, Integer pagina, Integer limite) {
        int tamano = validarLimite(limite);
        int indice = pagina == null || pagina < 0 ? 0 : pagina;
        EstadoRevision filtro = estadoRevision == null ? EstadoRevision.pendiente : estadoRevision;
        Specification<Anuncio> spec = (raiz, consulta, cb) -> cb.equal(raiz.get("estadoRevision"), filtro);
        Page<Anuncio> resultado = anuncios.findAll(spec,
                PageRequest.of(indice, tamano, Sort.by(Sort.Order.asc("fechaCreacion"), Sort.Order.asc("id"))));
        return new RespuestaColeccion<>(
                mapeador.paneles(resultado.getContent()),
                new PaginacionOffset(indice, tamano, resultado.getTotalElements(), resultado.getTotalPages()));
    }

    private static void exigirEstado(Anuncio anuncio, EstadoRevision esperado, String detalle) {
        if (anuncio.getEstadoRevision() != esperado) {
            throw new ReglaDeNegocioException(detalle);
        }
    }

    private static String exigirMotivo(String motivoRechazo) {
        if (motivoRechazo == null || motivoRechazo.isBlank()) {
            throw new ReglaDeNegocioException("El motivo es obligatorio para rechazar o retraer.");
        }
        return motivoRechazo.strip();
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
