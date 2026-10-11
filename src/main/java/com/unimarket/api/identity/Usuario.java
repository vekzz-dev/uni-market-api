package com.unimarket.api.identity;

import com.unimarket.api.shared.security.Rol;
import jakarta.persistence.Convert;
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
 * Identidad única del sistema (R1): perfil, credenciales y rol viven en la tabla
 * {@code usuarios}; su {@code id} coincide con el {@code sub} del JWT y se resuelve sin JOIN.
 *
 * <p>Soft-delete selectivo (R1): la baja escribe {@code fechaEliminacion}, nunca hay
 * {@code DELETE} físico; toda lectura filtra {@code fechaEliminacion IS NULL} y el email
 * queda libre para reutilización (R2, índice parcial {@code ux_usuarios_email_activo}).
 * El rol es de dominio cerrado {@code miembro/moderador/admin} (R3) y viaja como claim del
 * JWT, pero la autoridad de autorización es esta fila leída en cada request (D3).
 *
 * <p>{@code fechaActualizacion} la escribe la aplicación en cada UPDATE vía
 * {@code @PreUpdate}: no existen triggers en la BD (DBDD §1).
 */
@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String email;

    private String nombre;

    private String urlAvatar;

    private String hashContrasena;

    private Instant fechaVerificacionEmail;

    private boolean habilitado = true;

    private Instant fechaEliminacion;

    @Convert(converter = ConvertidorRol.class)
    private Rol rol = Rol.miembro;

    private Instant fechaCreacion;

    private Instant fechaActualizacion;

    /** Constructor para JPA. */
    protected Usuario() {
    }

    /** Auditoría de creación: ambas fechas nacen con el instante del INSERT. */
    @PrePersist
    void alCrear() {
        Instant ahora = Instant.now();
        if (fechaCreacion == null) {
            fechaCreacion = ahora;
        }
        fechaActualizacion = ahora;
    }

    /** Auditoría de escritura: la aplicación refresca la fecha en cada UPDATE (DBDD §1). */
    @PreUpdate
    void alActualizar() {
        fechaActualizacion = Instant.now();
    }

    /** Baja soft-delete (R1/R15): fija la fecha sin borrar la fila ni sus relaciones. */
    public void darDeBaja() {
        this.fechaEliminacion = Instant.now();
    }

    /** Activa si no fue dada de baja: filtra {@code fechaEliminacion IS NULL} (R15). */
    public boolean activa() {
        return fechaEliminacion == null;
    }

    /** Vigente para sesión: habilitada por admin, sin baja y con email confirmado (R15). */
    public boolean vigenteParaSesion() {
        return habilitado && activa() && fechaVerificacionEmail != null;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getUrlAvatar() {
        return urlAvatar;
    }

    public void setUrlAvatar(String urlAvatar) {
        this.urlAvatar = urlAvatar;
    }

    public String getHashContrasena() {
        return hashContrasena;
    }

    public void setHashContrasena(String hashContrasena) {
        this.hashContrasena = hashContrasena;
    }

    public Instant getFechaVerificacionEmail() {
        return fechaVerificacionEmail;
    }

    public void setFechaVerificacionEmail(Instant fechaVerificacionEmail) {
        this.fechaVerificacionEmail = fechaVerificacionEmail;
    }

    public boolean isHabilitado() {
        return habilitado;
    }

    public void setHabilitado(boolean habilitado) {
        this.habilitado = habilitado;
    }

    public Instant getFechaEliminacion() {
        return fechaEliminacion;
    }

    public void setFechaEliminacion(Instant fechaEliminacion) {
        this.fechaEliminacion = fechaEliminacion;
    }

    public Rol getRol() {
        return rol;
    }

    public void setRol(Rol rol) {
        this.rol = rol;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(Instant fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public Instant getFechaActualizacion() {
        return fechaActualizacion;
    }

    public void setFechaActualizacion(Instant fechaActualizacion) {
        this.fechaActualizacion = fechaActualizacion;
    }
}
