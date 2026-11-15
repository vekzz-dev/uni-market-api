package com.unimarket.api.moderation;

import com.unimarket.api.catalog.Decision;
import com.unimarket.api.catalog.DtosAnuncios;
import com.unimarket.api.catalog.EstadoRevision;
import com.unimarket.api.catalog.ServicioCatalogo;
import com.unimarket.api.catalog.ServicioRevision;
import com.unimarket.api.shared.error.ConflictoException;
import com.unimarket.api.shared.error.EstructuraInvalidaException;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.pagination.PaginacionOffset;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderación de anuncios y denuncias (R5, R19).
 *
 * <p>Vive en {@code moderation} y no en {@code catalog} por la frontera de módulos: la
 * dependencia permitida es {@code moderation → catalog}. Por eso el contador de denuncias
 * y el cierre de casos se hacen aquí, envolviendo las operaciones del servicio de revisión.
 */
@Service
public class ServicioModeracion {

    static final int LIMITE_DEFECTO = 20;
    static final int LIMITE_MAXIMO = 100;

    private final ServicioRevision revision;
    private final ServicioCatalogo catalogo;
    private final DenunciaRepository denuncias;

    public ServicioModeracion(ServicioRevision revision, ServicioCatalogo catalogo, DenunciaRepository denuncias) {
        this.revision = revision;
        this.catalogo = catalogo;
        this.denuncias = denuncias;
    }

    /**
     * Registra una denuncia (R19). El anuncio debe ser visible para quien denuncia —si no,
     * {@code 404} indistinguible (R4)— y no puede ser propio ({@code 422}). Una segunda
     * denuncia de la misma persona es {@code 409}: la UNIQUE es la autoridad y la
     * comprobación previa solo evita un error de integridad en el caso normal.
     */
    @Transactional
    public DtosModeracion.DenunciaPanel denunciar(
            UUID anuncioId, UUID denuncianteId, MotivoDenuncia motivo, String detalle) {
        DtosAnuncios.AnuncioDetalle anuncio = catalogo.detalle(anuncioId);
        if (anuncio.vendedorId().equals(denuncianteId)) {
            throw new ReglaDeNegocioException("No puedes denunciar tu propio anuncio.");
        }
        String limpio = detalle == null ? null : detalle.strip();
        if (motivo == MotivoDenuncia.otro && (limpio == null || limpio.isEmpty())) {
            throw new ReglaDeNegocioException("El detalle es obligatorio cuando el motivo es 'otro'.");
        }
        if (denuncias.existsByAnuncioIdAndDenuncianteId(anuncioId, denuncianteId)) {
            throw new ConflictoException("Ya denunciaste este anuncio.");
        }
        try {
            return DtosModeracion.DenunciaPanel.de(
                    denuncias.saveAndFlush(new Denuncia(anuncioId, denuncianteId, motivo, limpio)));
        } catch (DataIntegrityViolationException duplicada) {
            throw new ConflictoException("Ya denunciaste este anuncio.");
        }
    }

    /** Cola FIFO de denuncias por estado; orden estable con desempate por {@code id}. */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosModeracion.DenunciaPanel> denuncias(
            EstadoDenuncia estado, Integer pagina, Integer limite) {
        int tamano = validarLimite(limite);
        int indice = pagina == null || pagina < 0 ? 0 : pagina;
        EstadoDenuncia filtro = estado == null ? EstadoDenuncia.abierta : estado;
        Specification<Denuncia> spec = (raiz, consulta, cb) -> cb.equal(raiz.get("estado"), filtro);
        Page<Denuncia> resultado = denuncias.findAll(spec,
                PageRequest.of(indice, tamano, Sort.by(Sort.Order.asc("fechaCreacion"), Sort.Order.asc("id"))));
        return new RespuestaColeccion<>(
                resultado.getContent().stream().map(DtosModeracion.DenunciaPanel::de).toList(),
                new PaginacionOffset(indice, tamano, resultado.getTotalElements(), resultado.getTotalPages()));
    }

    /** Resuelve una denuncia abierta; una ya resuelta o un estado inválido → {@code 422}. */
    @Transactional
    public DtosModeracion.DenunciaPanel resolver(
            UUID denunciaId, EstadoDenuncia estado, String motivo, UUID actorId) {
        Denuncia denuncia = denuncias.findById(denunciaId)
                .orElseThrow(() -> new NoEncontradoException("La denuncia no existe."));
        denuncia.resolver(estado, motivo, actorId);
        return DtosModeracion.DenunciaPanel.de(denuncias.saveAndFlush(denuncia));
    }

    /** Cola de anuncios con el contador de denuncias abiertas de cada uno (una consulta por página). */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosModeracion.PanelModeracion> cola(
            EstadoRevision estadoRevision, Integer pagina, Integer limite) {
        RespuestaColeccion<DtosAnuncios.AnuncioPanel> base = revision.cola(estadoRevision, pagina, limite);
        Map<UUID, Long> conteos = new LinkedHashMap<>();
        if (!base.datos().isEmpty()) {
            denuncias.contarAbiertasPorAnuncio(base.datos().stream().map(DtosAnuncios.AnuncioPanel::id).toList())
                    .forEach(conteo -> conteos.put(conteo.getAnuncioId(), conteo.getTotal()));
        }
        return new RespuestaColeccion<>(
                base.datos().stream()
                        .map(panel -> DtosModeracion.PanelModeracion.de(panel, conteos.getOrDefault(panel.id(), 0L)))
                        .toList(),
                base.paginacion());
    }

    /**
     * Decisión de revisión (R5). Si el anuncio se rechaza o se retrae, sus denuncias abiertas
     * quedan cerradas como {@code revisada} con la auditoría del mismo moderador: se actuó
     * sobre el anuncio. Aprobar no cierra denuncias: no se ha actuado sobre ellas.
     */
    @Transactional
    public DtosModeracion.PanelModeracion decidir(
            UUID anuncioId, UUID revisorId, Decision decision, String motivoRechazo) {
        DtosAnuncios.AnuncioPanel panel = revision.decidir(anuncioId, revisorId, decision, motivoRechazo);
        if (decision != Decision.aprobar) {
            denuncias.findByAnuncioIdAndEstado(anuncioId, EstadoDenuncia.abierta)
                    .forEach(denuncia -> denuncia.cerrarPorDecisionDelAnuncio(revisorId));
            denuncias.flush();
        }
        return DtosModeracion.PanelModeracion.de(panel, abiertasDe(anuncioId));
    }

    private long abiertasDe(UUID anuncioId) {
        return denuncias.contarAbiertasPorAnuncio(List.of(anuncioId)).stream()
                .mapToLong(DenunciaRepository.ConteoDenuncias::getTotal)
                .findFirst()
                .orElse(0L);
    }

    static int validarLimite(Integer limite) {
        if (limite == null) {
            return LIMITE_DEFECTO;
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new EstructuraInvalidaException("El parámetro 'limite' debe estar entre 1 y 100.");
        }
        return limite;
    }
}
