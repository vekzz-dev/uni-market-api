package com.unimarket.api.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensaje de texto inmutable (R10): el texto se recorta y no puede quedar vacío
 * (el servicio responde 422 y el CHECK {@code chk_mensaje_no_vacio} lo respalda).
 * Sin {@code fechaActualizacion}: no hay edición ni borrado (PRD §7).
 */
@Entity
@Table(name = "mensajes")
public class Mensaje {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID conversacionId;

    private UUID remitenteId;

    private String texto;

    private boolean leido;

    private Instant fechaCreacion;

    /** Constructor para JPA. */
    protected Mensaje() {
    }

    public Mensaje(UUID conversacionId, UUID remitenteId, String texto) {
        this.conversacionId = conversacionId;
        this.remitenteId = remitenteId;
        this.texto = texto;
    }

    @PrePersist
    void alCrear() {
        if (fechaCreacion == null) {
            fechaCreacion = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversacionId() {
        return conversacionId;
    }

    public UUID getRemitenteId() {
        return remitenteId;
    }

    public String getTexto() {
        return texto;
    }

    public boolean isLeido() {
        return leido;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }
}
