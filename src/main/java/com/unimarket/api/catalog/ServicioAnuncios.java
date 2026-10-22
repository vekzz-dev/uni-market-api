package com.unimarket.api.catalog;

import com.unimarket.api.media.AlmacenObjetos;
import com.unimarket.api.media.PropiedadesMedia;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ciclo de vida del anuncio del vendedor (R5–R8): creación con imágenes pareadas en una
 * sola transacción, edición con reset de revisión (R6), visibilidad/vendido/baja y
 * ownership verificado aquí Y en el repositorio (R7, defensa en profundidad).
 *
 * <p>Compensación de storage (SDD §5.3): los objetos se suben ANTES del commit y se
 * eliminan si la transacción falla — nunca quedan huérfanos por un alta fallida.
 */
@Service
public class ServicioAnuncios {

    private static final BigDecimal PRECIO_MAXIMO = new BigDecimal("99999999.99");

    private final AnuncioRepository anuncios;
    private final AlmacenObjetos almacen;
    private final PropiedadesMedia medios;
    private final MapeadorAnuncios mapeador;

    public ServicioAnuncios(
            AnuncioRepository anuncios, AlmacenObjetos almacen, PropiedadesMedia medios, MapeadorAnuncios mapeador) {
        this.anuncios = anuncios;
        this.almacen = almacen;
        this.medios = medios;
        this.mapeador = mapeador;
    }

    /** Crea un anuncio SIEMPRE {@code pendiente} (R5) con hasta 5 imágenes validadas por contenido (R8). */
    @Transactional
    public DtosAnuncios.AnuncioPanel crear(
            UUID vendedorId,
            String nombre,
            BigDecimal precio,
            String descripcion,
            Integer categoriaId,
            List<MultipartFile> imagenes) {
        String nombreLimpio = validarNombre(nombre);
        validarPrecio(precio);
        List<ImagenValidada> validas = validarImagenes(imagenes);

        List<String> rutas = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        try {
            for (ImagenValidada imagen : validas) {
                String ruta = "anuncios/" + vendedorId + "/" + UUID.randomUUID() + "." + imagen.extension();
                urls.add(almacen.guardar(ruta, imagen.contentType(), imagen.bytes()));
                rutas.add(ruta);
            }
            Anuncio anuncio = new Anuncio();
            anuncio.setVendedorId(vendedorId);
            anuncio.setNombre(nombreLimpio);
            anuncio.setPrecio(precio);
            anuncio.setDescripcion(descripcion);
            anuncio.setCategoriaId(categoriaId);
            anuncio.setImagenesUrls(new ArrayList<>(urls));
            anuncio.setImagenesPaths(new ArrayList<>(rutas));
            return mapeador.panel(anuncios.saveAndFlush(anuncio));
        } catch (RuntimeException fallo) {
            rutas.forEach(almacen::eliminar);
            throw fallo;
        }
    }

    /** Edita el contenido; si estaba {@code aprobado|rechazado} vuelve a {@code pendiente} (R6). */
    @Transactional
    public DtosAnuncios.AnuncioPanel editar(UUID actorId, UUID anuncioId, DtosAnuncios.ActualizarRequest cambios) {
        Anuncio anuncio = cargarDelVendedor(anuncioId, actorId);
        boolean hayCambio = false;
        if (cambios.nombre() != null) {
            anuncio.setNombre(validarNombre(cambios.nombre()));
            hayCambio = true;
        }
        if (cambios.precio() != null) {
            validarPrecio(cambios.precio());
            anuncio.setPrecio(cambios.precio());
            hayCambio = true;
        }
        if (cambios.descripcion() != null) {
            anuncio.setDescripcion(cambios.descripcion());
            hayCambio = true;
        }
        if (cambios.categoriaId() != null) {
            anuncio.setCategoriaId(cambios.categoriaId());
            hayCambio = true;
        }
        if (hayCambio && anuncio.getEstadoRevision() != EstadoRevision.pendiente) {
            anuncio.setEstadoRevision(EstadoRevision.pendiente);
            anuncio.limpiarTrailRevision();
        }
        return mapeador.panel(anuncios.saveAndFlush(anuncio));
    }

    /** Alterna {@code disponible↔inactivo}; solo sobre revisión aprobada (PRD §5). */
    @Transactional
    public DtosAnuncios.AnuncioPanel alternarVisibilidad(UUID actorId, UUID anuncioId, boolean visible) {
        Anuncio anuncio = cargarDelVendedor(anuncioId, actorId);
        if (anuncio.getEstadoRevision() != EstadoRevision.aprobado) {
            throw new ReglaDeNegocioException("Solo un anuncio aprobado puede cambiar de visibilidad.");
        }
        if (anuncio.getEstado() != EstadoAnuncio.disponible && anuncio.getEstado() != EstadoAnuncio.inactivo) {
            throw new ReglaDeNegocioException("El estado actual del anuncio no permite cambiar su visibilidad.");
        }
        anuncio.setEstado(visible ? EstadoAnuncio.disponible : EstadoAnuncio.inactivo);
        return mapeador.panel(anuncios.saveAndFlush(anuncio));
    }

    /** Marca vendido desde {@code disponible|inactivo}. */
    @Transactional
    public DtosAnuncios.AnuncioPanel marcarVendido(UUID actorId, UUID anuncioId) {
        Anuncio anuncio = cargarDelVendedor(anuncioId, actorId);
        if (anuncio.getEstado() != EstadoAnuncio.disponible && anuncio.getEstado() != EstadoAnuncio.inactivo) {
            throw new ReglaDeNegocioException("El estado actual del anuncio no permite marcarlo vendido.");
        }
        anuncio.setEstado(EstadoAnuncio.vendido);
        return mapeador.panel(anuncios.saveAndFlush(anuncio));
    }

    /** Baja lógica (R1): conserva conversaciones y objetos de imagen; no hay borrado físico. */
    @Transactional
    public void darDeBaja(UUID actorId, UUID anuncioId) {
        Anuncio anuncio = cargarDelVendedor(anuncioId, actorId);
        anuncio.setEstado(EstadoAnuncio.eliminado);
        anuncios.saveAndFlush(anuncio);
    }

    /**
     * Reemplaza TODAS las imágenes del anuncio (N6, R8): sube las nuevas antes del
     * commit, sustituye los arrays pareados, resetea la revisión si hacía falta (R6)
     * y elimina las reemplazadas SOLO tras el commit (nunca antes: si la tx falla,
     * las viejas siguen siendo las válidas).
     */
    @Transactional
    public DtosAnuncios.AnuncioPanel reemplazarImagenes(UUID actorId, UUID anuncioId, List<MultipartFile> imagenes) {
        Anuncio anuncio = cargarDelVendedor(anuncioId, actorId);
        List<String> rutasViejas = List.copyOf(anuncio.getImagenesPaths());
        List<ImagenValidada> validas = validarImagenes(imagenes);

        List<String> rutasNuevas = new ArrayList<>();
        List<String> urlsNuevas = new ArrayList<>();
        try {
            for (ImagenValidada imagen : validas) {
                String ruta = "anuncios/" + actorId + "/" + UUID.randomUUID() + "." + imagen.extension();
                urlsNuevas.add(almacen.guardar(ruta, imagen.contentType(), imagen.bytes()));
                rutasNuevas.add(ruta);
            }
            anuncio.setImagenesUrls(new ArrayList<>(urlsNuevas));
            anuncio.setImagenesPaths(new ArrayList<>(rutasNuevas));
            if (anuncio.getEstadoRevision() != EstadoRevision.pendiente) {
                anuncio.setEstadoRevision(EstadoRevision.pendiente);
                anuncio.limpiarTrailRevision();
            }
            Anuncio guardado = anuncios.saveAndFlush(anuncio);
            borrarTrasCommit(rutasViejas);
            return mapeador.panel(guardado);
        } catch (RuntimeException fallo) {
            rutasNuevas.forEach(almacen::eliminar);
            throw fallo;
        }
    }

    /** Diferido hasta el commit; sin transacción activa (tests planos) borra en el acto. */
    private void borrarTrasCommit(List<String> rutas) {
        if (rutas.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rutas.forEach(almacen::eliminar);
                }
            });
        } else {
            rutas.forEach(almacen::eliminar);
        }
    }

    /** Carga con ownership aplicado: 404 si no existe, 403 si es de otro vendedor (R7). */
    private Anuncio cargarDelVendedor(UUID anuncioId, UUID actorId) {
        Anuncio anuncio = anuncios.findById(anuncioId)
                .orElseThrow(() -> new NoEncontradoException("El anuncio no existe."));
        if (!anuncio.getVendedorId().equals(actorId)) {
            throw new ProhibidoException("No puedes operar sobre un anuncio que no es tuyo.");
        }
        return anuncio;
    }

    private static String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new ReglaDeNegocioException("El nombre del anuncio es obligatorio.");
        }
        return nombre.strip();
    }

    private static void validarPrecio(BigDecimal precio) {
        if (precio == null || precio.compareTo(BigDecimal.ZERO) < 0 || precio.compareTo(PRECIO_MAXIMO) > 0) {
            throw new ReglaDeNegocioException("El precio debe estar entre 0 y 99999999.99.");
        }
    }

    private List<ImagenValidada> validarImagenes(List<MultipartFile> imagenes) {
        if (imagenes == null || imagenes.isEmpty()) {
            throw new ReglaDeNegocioException("El anuncio requiere al menos una imagen.");
        }
        if (imagenes.size() > medios.anuncioMaxImagenes()) {
            throw new ReglaDeNegocioException("El anuncio admite hasta " + medios.anuncioMaxImagenes() + " imágenes.");
        }
        List<ImagenValidada> validas = new ArrayList<>();
        for (MultipartFile archivo : imagenes) {
            if (archivo == null || archivo.getSize() > medios.imagenMaxBytes()) {
                throw new ReglaDeNegocioException("Cada imagen debe pesar como máximo 5 MB.");
            }
            byte[] bytes = leer(archivo);
            String tipo = detectarTipo(bytes);
            if (tipo == null || !medios.tiposImagen().contains(tipo)) {
                throw new ReglaDeNegocioException("Formato de imagen no permitido: usa jpeg, png, gif o webp.");
            }
            validas.add(new ImagenValidada(bytes, tipo, extensionDe(tipo)));
        }
        return validas;
    }

    private static byte[] leer(MultipartFile archivo) {
        try {
            return archivo.getBytes();
        } catch (IOException excepcion) {
            throw new UncheckedIOException("No se pudo leer la imagen subida.", excepcion);
        }
    }

    /**
     * Detecta el tipo por bytes de firma (magic bytes), no por el content-type declarado:
     * un cliente no decide el tipo de lo que sube (R8).
     */
    private static String detectarTipo(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String extensionDe(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw new IllegalStateException("Tipo sin extensión: " + contentType.toLowerCase(Locale.ROOT));
        };
    }

    /** Imagen ya validada: bytes, tipo por firma y extensión segura para la clave. */
    private record ImagenValidada(byte[] bytes, String contentType, String extension) {
    }
}
