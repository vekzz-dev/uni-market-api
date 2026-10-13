package com.unimarket.api.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Hilo de chat por anuncio entre un comprador y el vendedor (R9): exactamente una por par
 * {@code (anuncioId, compradorId)} — la UNIQUE lo impone a nivel de datos. El
 * {@code vendedorId} SIEMPRE se deriva del anuncio en el servidor (el valor del cliente
 * se ignora) y {@code fechaActualizacion} se refresca en cada envío para ordenar
 * "mis conversaciones".
 */
@Entity
@Table(name = "conversaciones")
public class Conversacion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID anuncioId;

    private UUID compradorId;

    private UUID vendedorId;

    private Instant fechaCreacion;

    private Instant fechaActualizacion;

    /** Constructor para JPA. */
    protected Conversacion() {
    }

    public Conversacion(UUID anuncioId, UUID compradorId, UUID vendedorId) {
        this.anuncioId = anuncioId;
        this.compradorId = compradorId;
        this.vendedorId = vendedorId;
    }

    @PrePersist
    void alCrear() {
        Instant ahora = Instant.now();
        if (fechaCreacion == null) {
            fechaCreacion = ahora;
        }
        fechaActualizacion = ahora;
    }

    @PreUpdate
    void alActualizar() {
        fechaActualizacion = Instant.now();
    }

    /** Participación verificada en el servicio (R10); aquí solo la semántica del hilo. */
    boolean esParticipante(UUID usuarioId) {
        return compradorId.equals(usuarioId) || vendedorId.equals(usuarioId);
    }

    /** El otro extremo del hilo (para validar su actividad al enviar, R16). */
    UUID contraparteDe(UUID usuarioId) {
        return compradorId.equals(usuarioId) ? vendedorId : compradorId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAnuncioId() {
        return anuncioId;
    }

    public UUID getCompradorId() {
        return compradorId;
    }

    public UUID getVendedorId() {
        return vendedorId;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public Instant getFechaActualizacion() {
        return fechaActualizacion;
    }

    public void setFechaActualizacion(Instant fechaActualizacion) {
        this.fechaActualizacion = fechaActualizacion;
    }
}
