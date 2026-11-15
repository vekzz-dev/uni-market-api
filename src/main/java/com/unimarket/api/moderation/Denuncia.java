package com.unimarket.api.moderation;

import com.unimarket.api.shared.error.ReglaDeNegocioException;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Denuncia de un anuncio (R19, issue #9): evidencia para el moderador, nunca un disparador
 * automático. No retira el anuncio ni cambia su revisión: la decisión sigue siendo de una
 * persona identificada (R5).
 *
 * <p>Una denuncia por persona y anuncio la impone la UNIQUE de la tabla. El denunciante no
 * se expone en ninguna respuesta pública ni del panel del vendedor.
 */
@Entity
@Table(name = "denuncias")
public class Denuncia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID anuncioId;

    private UUID denuncianteId;

    @Convert(converter = ConvertidorMotivoDenuncia.class)
    private MotivoDenuncia motivo;

    private String detalle;

    @Convert(converter = ConvertidorEstadoDenuncia.class)
    private EstadoDenuncia estado = EstadoDenuncia.abierta;

    private Instant fechaCreacion;

    private UUID resueltoPor;

    private Instant fechaResolucion;

    /** Justificación del cierre (opcional): "duplicado", "no procede", etc. (R19). */
    private String motivoResolucion;

    /** Constructor para JPA. */
    protected Denuncia() {
    }

    /** Denuncia nueva: nace abierta y sin trail de resolución. */
    public Denuncia(UUID anuncioId, UUID denuncianteId, MotivoDenuncia motivo, String detalle) {
        this.anuncioId = anuncioId;
        this.denuncianteId = denuncianteId;
        this.motivo = motivo;
        this.detalle = detalle;
        this.estado = EstadoDenuncia.abierta;
    }

    @PrePersist
    void alCrear() {
        if (fechaCreacion == null) {
            fechaCreacion = Instant.now();
        }
    }

    /**
     * Cierra la denuncia con auditoría. Solo se resuelve una denuncia {@code abierta}:
     * reabrir o re-resolver sería perder el rastro de quién decidió primero.
     */
    void resolver(EstadoDenuncia nuevo, String motivoResolucion, UUID revisorId) {
        if (nuevo == EstadoDenuncia.abierta) {
            throw new ReglaDeNegocioException("Una denuncia solo se resuelve como revisada o descartada.");
        }
        if (estado != EstadoDenuncia.abierta) {
            throw new ReglaDeNegocioException("La denuncia ya fue resuelta.");
        }
        this.estado = nuevo;
        this.motivoResolucion = motivoResolucion == null || motivoResolucion.isBlank()
                ? null
                : motivoResolucion.strip();
        this.resueltoPor = revisorId;
        this.fechaResolucion = Instant.now();
    }

    /** Cierre masivo cuando el moderador retrae o rechaza el anuncio (no toca las ya resueltas). */
    void cerrarPorDecisionDelAnuncio(UUID revisorId) {
        if (estado == EstadoDenuncia.abierta) {
            this.estado = EstadoDenuncia.revisada;
            this.resueltoPor = revisorId;
            this.fechaResolucion = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getAnuncioId() {
        return anuncioId;
    }

    public UUID getDenuncianteId() {
        return denuncianteId;
    }

    public MotivoDenuncia getMotivo() {
        return motivo;
    }

    public String getDetalle() {
        return detalle;
    }

    public EstadoDenuncia getEstado() {
        return estado;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public UUID getResueltoPor() {
        return resueltoPor;
    }

    public Instant getFechaResolucion() {
        return fechaResolucion;
    }

    public String getMotivoResolucion() {
        return motivoResolucion;
    }
}
