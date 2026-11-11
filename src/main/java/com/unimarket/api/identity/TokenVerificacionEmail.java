package com.unimarket.api.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Token de confirmación de email (R11, tabla {@code tokens_verificacion_email}): ventana de
 * 24 horas, un solo uso transaccional y a lo sumo uno activo por usuario (índice parcial
 * {@code ux_tokens_email_activos}).
 *
 * <p>Se persiste el hash SHA-256 del valor crudo, nunca el plaintext; la búsqueda es por
 * hash del valor presentado. El token crudo solo viaja una vez, por email.
 */
@Entity
@Table(name = "tokens_verificacion_email")
public class TokenVerificacionEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID usuarioId;

    private String hashToken;

    private Instant fechaExpiracion;

    private Instant fechaUso;

    /** Intentos fallidos consumidos: al llegar al máximo el código se invalida (R11). */
    private int intentos;

    private Instant fechaCreacion;

    /** Constructor para JPA. */
    protected TokenVerificacionEmail() {
    }

    /** La creación fija el instante de emisión. */
    @PrePersist
    void alCrear() {
        if (fechaCreacion == null) {
            fechaCreacion = Instant.now();
        }
    }

    /** Máximo de intentos fallidos antes de invalidar el código (R11). */
    public static final int MAX_INTENTOS = 5;

    /** Activo si no fue consumido, no expiró y no agotó sus intentos (R11). */
    public boolean activo(Instant ahora) {
        return fechaUso == null && ahora.isBefore(fechaExpiracion) && !agotado();
    }

    /** Suma un intento fallido; el llamador invalida el código al llegar al máximo. */
    public void registrarIntentoFallido() {
        intentos++;
    }

    /** Sin intentos disponibles: el código deja de servir aunque no haya expirado. */
    public boolean agotado() {
        return intentos >= MAX_INTENTOS;
    }

    public int getIntentos() {
        return intentos;
    }

    /** Consumo de un solo uso: fija {@code fechaUso} en la misma transacción (R11). */
    public void marcarUso(Instant ahora) {
        this.fechaUso = ahora;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(UUID usuarioId) {
        this.usuarioId = usuarioId;
    }

    public String getHashToken() {
        return hashToken;
    }

    public void setHashToken(String hashToken) {
        this.hashToken = hashToken;
    }

    public Instant getFechaExpiracion() {
        return fechaExpiracion;
    }

    public void setFechaExpiracion(Instant fechaExpiracion) {
        this.fechaExpiracion = fechaExpiracion;
    }

    public Instant getFechaUso() {
        return fechaUso;
    }

    public void setFechaUso(Instant fechaUso) {
        this.fechaUso = fechaUso;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(Instant fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }
}
