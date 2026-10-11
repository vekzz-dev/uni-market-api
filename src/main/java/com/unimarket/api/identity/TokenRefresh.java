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
 * Refresh token opaco (R12, tabla {@code tokens_refresh}): vigencia de 30 días almacenado
 * solo como hash SHA-256. Cada refresh rota en la misma transacción (anterior marcado usado
 * + nuevo insertado) compartiendo {@code familiaId}.
 *
 * <p>Presentar un token ya usado (posible robo) revoca TODA la familia
 * ({@code fechaRevocacion} en cada miembro). La revocación (logout, reuso, baja) fija
 * {@code fechaUso} además de {@code fechaRevocacion} para liberar el slot del índice
 * parcial {@code ux_tokens_refresh_activos} y que el login posterior pueda emitir.
 */
@Entity
@Table(name = "tokens_refresh")
public class TokenRefresh {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID usuarioId;

    private String hashToken;

    private Instant fechaExpiracion;

    private Instant fechaUso;

    private Instant fechaCreacion;

    private UUID familiaId;

    private Instant fechaRevocacion;

    /** Constructor para JPA. */
    protected TokenRefresh() {
    }

    /** La creación fija el instante de emisión. */
    @PrePersist
    void alCrear() {
        if (fechaCreacion == null) {
            fechaCreacion = Instant.now();
        }
    }

    /** Activo si no fue usado, ni expiró, ni su familia fue revocada (R12). */
    public boolean activo(Instant ahora) {
        return fechaUso == null && fechaRevocacion == null && ahora.isBefore(fechaExpiracion);
    }

    /** Ya rotado pero no revocado: presentarlo de nuevo es señal de posible robo (R12). */
    public boolean reutilizado() {
        return fechaUso != null && fechaRevocacion == null;
    }

    /** Rotación normal: marca el uso; el nuevo token se inserta en la misma transacción. */
    public void marcarUso(Instant ahora) {
        this.fechaUso = ahora;
    }

    /** Revocación server-side: cierra el token y libera el slot de activo (R12). */
    public void revocar(Instant ahora) {
        this.fechaUso = ahora;
        this.fechaRevocacion = ahora;
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

    public UUID getFamiliaId() {
        return familiaId;
    }

    public void setFamiliaId(UUID familiaId) {
        this.familiaId = familiaId;
    }

    public Instant getFechaRevocacion() {
        return fechaRevocacion;
    }

    public void setFechaRevocacion(Instant fechaRevocacion) {
        this.fechaRevocacion = fechaRevocacion;
    }
}
