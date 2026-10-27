package com.unimarket.api.catalog;

import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anuncio publicable (R4/R6/R7/R8): nace {@code pendiente} sin excepción de rol (R5),
 * la edición de contenido resetea la revisión (R6) y la baja es {@code estado=eliminado},
 * nunca física (R1: conserva conversaciones y objetos de imagen).
 *
 * <p>Las imágenes viven en dos arrays jsonb pareados por posición (R8): {@code imagenesUrls}
 * (público, lo único que sale al wire) e {@code imagenesPaths} (claves internas del storage,
 * jamás expuestas). {@code fechaActualizacion} la escribe la aplicación vía {@code @PreUpdate}
 * (DBDD §1: sin triggers).
 */
@Entity
@Table(name = "anuncios")
public class Anuncio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String nombre;

    @Column(precision = 10, scale = 2)
    private BigDecimal precio;

    private String descripcion;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> imagenesUrls = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> imagenesPaths = new ArrayList<>();

    private UUID vendedorId;

    private Integer categoriaId;

    @Convert(converter = ConvertidorEstadoAnuncio.class)
    private EstadoAnuncio estado = EstadoAnuncio.disponible;

    @Convert(converter = ConvertidorEstadoRevision.class)
    private EstadoRevision estadoRevision = EstadoRevision.pendiente;

    private UUID revisadoPor;

    private Instant fechaRevision;

    private String motivoRechazo;

    @Convert(converter = ConvertidorOrigenRechazo.class)
    private OrigenRechazo origenRechazo;

    private Instant fechaCreacion;

    private Instant fechaActualizacion;

    /** Constructor para JPA. */
    protected Anuncio() {
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

    /** Limpia el trail de moderación al reingresar a la cola (R6). */
    void limpiarTrailRevision() {
        revisadoPor = null;
        fechaRevision = null;
        motivoRechazo = null;
        origenRechazo = null;
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public void setPrecio(BigDecimal precio) {
        this.precio = precio;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public List<String> getImagenesUrls() {
        return imagenesUrls;
    }

    public void setImagenesUrls(List<String> imagenesUrls) {
        this.imagenesUrls = imagenesUrls;
    }

    public List<String> getImagenesPaths() {
        return imagenesPaths;
    }

    public void setImagenesPaths(List<String> imagenesPaths) {
        this.imagenesPaths = imagenesPaths;
    }

    public UUID getVendedorId() {
        return vendedorId;
    }

    public void setVendedorId(UUID vendedorId) {
        this.vendedorId = vendedorId;
    }

    public Integer getCategoriaId() {
        return categoriaId;
    }

    public void setCategoriaId(Integer categoriaId) {
        this.categoriaId = categoriaId;
    }

    public EstadoAnuncio getEstado() {
        return estado;
    }

    public void setEstado(EstadoAnuncio estado) {
        this.estado = estado;
    }

    public EstadoRevision getEstadoRevision() {
        return estadoRevision;
    }

    public void setEstadoRevision(EstadoRevision estadoRevision) {
        this.estadoRevision = estadoRevision;
    }

    public UUID getRevisadoPor() {
        return revisadoPor;
    }

    public void setRevisadoPor(UUID revisadoPor) {
        this.revisadoPor = revisadoPor;
    }

    public Instant getFechaRevision() {
        return fechaRevision;
    }

    public void setFechaRevision(Instant fechaRevision) {
        this.fechaRevision = fechaRevision;
    }

    public String getMotivoRechazo() {
        return motivoRechazo;
    }

    public void setMotivoRechazo(String motivoRechazo) {
        this.motivoRechazo = motivoRechazo;
    }

    public OrigenRechazo getOrigenRechazo() {
        return origenRechazo;
    }

    public void setOrigenRechazo(OrigenRechazo origenRechazo) {
        this.origenRechazo = origenRechazo;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public Instant getFechaActualizacion() {
        return fechaActualizacion;
    }
}
