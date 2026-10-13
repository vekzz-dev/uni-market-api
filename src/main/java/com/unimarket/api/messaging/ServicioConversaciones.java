package com.unimarket.api.messaging;

import com.unimarket.api.catalog.DtosAnuncios;
import com.unimarket.api.catalog.ServicioCatalogo;
import com.unimarket.api.shared.error.EstructuraInvalidaException;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.pagination.CursorCodec;
import com.unimarket.api.shared.pagination.PaginacionCursor;
import com.unimarket.api.shared.pagination.PaginacionOffset;
import com.unimarket.api.shared.pagination.RegistroCursor;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import com.unimarket.api.shared.security.EstadoUsuarioProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mensajería comprador–vendedor (R9/R10/R16, SDD §5.4): dedupe natural por par,
 * apertura solo sobre anuncios visibles con vendedor activo, envío con ambos participantes
 * activos, historial inmutable con cursor hacia atrás y no leídos como acción explícita.
 */
@Service
public class ServicioConversaciones {

    static final int LIMITE_DEFECTO = 20;
    static final int LIMITE_MAXIMO = 100;

    private final ConversacionRepository conversaciones;
    private final MensajeRepository mensajes;
    private final ServicioCatalogo catalogo;
    private final EstadoUsuarioProvider estadoUsuarios;
    private final CursorCodec cursorCodec;

    public ServicioConversaciones(
            ConversacionRepository conversaciones,
            MensajeRepository mensajes,
            ServicioCatalogo catalogo,
            EstadoUsuarioProvider estadoUsuarios,
            CursorCodec cursorCodec) {
        this.conversaciones = conversaciones;
        this.mensajes = mensajes;
        this.catalogo = catalogo;
        this.estadoUsuarios = estadoUsuarios;
        this.cursorCodec = cursorCodec;
    }

    /** Resultado de la apertura: {@code creada} distingue 201 (nueva) de 200 (reusada), R9. */
    public record Apertura(DtosMensajeria.ConversacionResumen conversacion, boolean creada) {
    }

    /**
     * Abre —o reutiliza— la conversación del par {@code (anuncio, comprador)} (R9/R16).
     *
     * @throws NoEncontradoException  si el anuncio no existe o no es visible, o el vendedor
     *                                no está activo (404 indistinguible, R16)
     * @throws ReglaDeNegocioException si el comprador es el propio vendedor (422, R9)
     */
    @Transactional
    public Apertura abrir(UUID compradorId, UUID anuncioId) {
        DtosAnuncios.AnuncioPublico anuncio = catalogo.detalle(anuncioId);
        UUID vendedorId = anuncio.vendedorId();
        if (vendedorId.equals(compradorId)) {
            throw new ReglaDeNegocioException("No puedes abrir una conversación con tu propio anuncio.");
        }
        boolean vendedorActivo = estadoUsuarios.porId(vendedorId)
                .filter(estado -> estado.habilitado() && !estado.eliminado())
                .isPresent();
        if (!vendedorActivo) {
            throw new NoEncontradoException("El anuncio no existe o no es visible.");
        }
        return conversaciones.findByAnuncioIdAndCompradorId(anuncioId, compradorId)
                .map(existente -> new Apertura(DtosMensajeria.ConversacionResumen.de(existente), false))
                .orElseGet(() -> {
                    Conversacion creada = conversaciones.saveAndFlush(new Conversacion(anuncioId, compradorId, vendedorId));
                    return new Apertura(DtosMensajeria.ConversacionResumen.de(creada), true);
                });
    }

    /** Panel "mis conversaciones": participante, orden por actividad, offset + total. */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosMensajeria.ConversacionItem> misConversaciones(UUID usuarioId, Integer pagina, Integer limite) {
        int tamano = validarLimite(limite);
        int indice = pagina == null || pagina < 0 ? 0 : pagina;
        Page<Conversacion> resultado = conversaciones.findAll(
                EspecificacionesMensajeria.deParticipante(usuarioId),
                PageRequest.of(indice, tamano, Sort.by(Sort.Order.desc("fechaActualizacion"), Sort.Order.desc("id"))));
        return new RespuestaColeccion<>(
                resultado.getContent().stream().map(conversacion -> item(conversacion, usuarioId)).toList(),
                new PaginacionOffset(indice, tamano, resultado.getTotalElements(), resultado.getTotalPages()));
    }

    /** Detalle para participantes (403 para el resto, R10). */
    @Transactional(readOnly = true)
    public DtosMensajeria.ConversacionDetalle detalle(UUID conversacionId, UUID usuarioId) {
        Conversacion conversacion = cargarComoParticipante(conversacionId, usuarioId);
        return new DtosMensajeria.ConversacionDetalle(
                conversacion.getId(),
                conversacion.getAnuncioId(),
                conversacion.getCompradorId(),
                conversacion.getVendedorId(),
                conversacion.getFechaCreacion(),
                conversacion.getFechaActualizacion(),
                catalogo.resumenSinVisibilidad(conversacion.getAnuncioId()),
                mensajes.countByConversacionIdAndLeidoFalseAndRemitenteIdNot(conversacionId, usuarioId));
    }

    /** Historial inmutable con cursor hacia atrás: página inicial = mensajes más recientes (D4). */
    @Transactional(readOnly = true)
    public RespuestaColeccion<DtosMensajeria.MensajeRespuesta> historial(
            UUID conversacionId, UUID usuarioId, String cursor, Integer limite) {
        cargarComoParticipante(conversacionId, usuarioId);
        int tamano = validarLimite(limite);
        Specification<Mensaje> filtro = EspecificacionesMensajeria.deConversacion(conversacionId);
        if (cursor != null && !cursor.isBlank()) {
            RegistroCursor estado = cursorCodec.decodificar(cursor);
            filtro = filtro.and(EspecificacionesMensajeria.antesDelCursor(estado.fechaCreacion(), estado.id()));
        }
        Page<Mensaje> pagina = mensajes.findAll(filtro,
                PageRequest.of(0, tamano + 1, Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"))));
        List<Mensaje> filas = pagina.getContent();
        boolean haySiguiente = filas.size() > tamano;
        List<Mensaje> visibles = haySiguiente ? filas.subList(0, tamano) : filas;
        String cursorSiguiente = null;
        if (haySiguiente && !visibles.isEmpty()) {
            Mensaje ultimo = visibles.get(visibles.size() - 1);
            cursorSiguiente = cursorCodec.codificar(new RegistroCursor(ultimo.getFechaCreacion(), ultimo.getId()));
        }
        return new RespuestaColeccion<>(
                visibles.stream().map(DtosMensajeria.MensajeRespuesta::de).toList(),
                new PaginacionCursor(cursorSiguiente));
    }

    /** Envía un mensaje de texto (R10/R16): el remitente activo lo garantiza el filtro de autoridad. */
    @Transactional
    public DtosMensajeria.MensajeRespuesta enviar(UUID conversacionId, UUID remitenteId, String texto) {
        Conversacion conversacion = cargarComoParticipante(conversacionId, remitenteId);
        String limpio = texto == null ? null : texto.strip();
        if (limpio == null || limpio.isEmpty()) {
            throw new ReglaDeNegocioException("El mensaje no puede estar vacío.");
        }
        UUID contraparte = conversacion.contraparteDe(remitenteId);
        boolean contraparteActiva = estadoUsuarios.porId(contraparte)
                .filter(estado -> estado.habilitado() && !estado.eliminado())
                .isPresent();
        if (!contraparteActiva) {
            throw new ReglaDeNegocioException("El otro participante no está disponible para recibir mensajes.");
        }
        Mensaje mensaje = mensajes.saveAndFlush(new Mensaje(conversacionId, remitenteId, limpio));
        conversacion.setFechaActualizacion(Instant.now());
        conversaciones.saveAndFlush(conversacion);
        return DtosMensajeria.MensajeRespuesta.de(mensaje);
    }

    /** Marcado explícito de lecturas: solo recibidos, idempotente (PRD §5). */
    @Transactional
    public void marcarLecturas(UUID conversacionId, UUID usuarioId) {
        cargarComoParticipante(conversacionId, usuarioId);
        mensajes.marcarLeidos(conversacionId, usuarioId);
    }

    private DtosMensajeria.ConversacionItem item(Conversacion conversacion, UUID usuarioId) {
        return new DtosMensajeria.ConversacionItem(
                conversacion.getId(),
                conversacion.getAnuncioId(),
                conversacion.getCompradorId(),
                conversacion.getVendedorId(),
                conversacion.getFechaCreacion(),
                conversacion.getFechaActualizacion(),
                catalogo.resumenSinVisibilidad(conversacion.getAnuncioId()),
                mensajes.findTopByConversacionIdOrderByFechaCreacionDescIdDesc(conversacion.getId())
                        .map(DtosMensajeria.UltimoMensaje::de)
                        .orElse(null),
                mensajes.countByConversacionIdAndLeidoFalseAndRemitenteIdNot(conversacion.getId(), usuarioId));
    }

    /** 404 si no existe; 403 si no es comprador ni vendedor (R10). */
    private Conversacion cargarComoParticipante(UUID conversacionId, UUID usuarioId) {
        Conversacion conversacion = conversaciones.findById(conversacionId)
                .orElseThrow(() -> new NoEncontradoException("La conversación no existe."));
        if (!conversacion.esParticipante(usuarioId)) {
            throw new ProhibidoException("No participas en esta conversación.");
        }
        return conversacion;
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
