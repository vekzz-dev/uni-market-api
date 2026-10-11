package com.unimarket.api.identity;

import com.unimarket.api.media.AlmacenObjetos;
import com.unimarket.api.media.PropiedadesMedia;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Perfil, avatar y baja (R1/R2/R8/R15): el email es de solo lectura; el avatar se valida
 * (2 MB, jpeg/png/gif/webp desde la única fuente de límites) y se sirve por
 * {@code /media/**}; la baja es soft-delete y corta la sesión.
 */
@Service
public class ServicioPerfil {

    private static final Map<String, String> EXTENSION_POR_TIPO = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp");

    private static final Set<String> EXTENSIONES = Set.of("jpg", "jpeg", "png", "gif", "webp");

    /** Firma mínima PNG (8 bytes) exigida además del content-type declarado. */
    private static final byte[] FIRMA_PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final UsuarioRepository usuarios;
    private final ServicioSesion sesiones;
    private final TokenVerificacionEmailRepository verificaciones;
    private final TokenRestablecimientoRepository restablecimientos;
    private final AlmacenObjetos almacen;
    private final PropiedadesMedia medios;

    public ServicioPerfil(
            UsuarioRepository usuarios,
            ServicioSesion sesiones,
            TokenVerificacionEmailRepository verificaciones,
            TokenRestablecimientoRepository restablecimientos,
            AlmacenObjetos almacen,
            PropiedadesMedia medios) {
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.verificaciones = verificaciones;
        this.restablecimientos = restablecimientos;
        this.almacen = almacen;
        this.medios = medios;
    }

    /** Lee el perfil vigente; la baja o ausencia responde indistinguible vía filtro (R15). */
    @Transactional(readOnly = true)
    public Dtos.UsuarioRespuesta leer(UUID usuarioId) {
        return Dtos.UsuarioRespuesta.de(vigente(usuarioId));
    }

    /** Solo el nombre es editable: el email nunca se escribe tras el registro (R2). */
    @Transactional
    public Dtos.UsuarioRespuesta actualizar(UUID usuarioId, String nombreCrudo) {
        String nombre = nombreCrudo == null ? "" : nombreCrudo.strip();
        if (nombre.isEmpty() || nombre.length() > 255) {
            throw new ReglaDeNegocioException("El nombre no puede estar vacío.");
        }
        Usuario usuario = vigente(usuarioId);
        usuario.setNombre(nombre);
        return Dtos.UsuarioRespuesta.de(usuario);
    }

    /**
     * Sube o reemplaza el avatar (R8): multipart exclusivo vía API, máximo 2 MB y tipos
     * jpeg/png/gif/webp. El fallo de validación no sube objetos.
     *
     * @throws ReglaDeNegocioException si falta el archivo o viola tamaño/tipo (422)
     */
    @Transactional
    public Dtos.UsuarioRespuesta subirAvatar(UUID usuarioId, MultipartFile archivo) {
        Usuario usuario = vigente(usuarioId);
        if (archivo == null || archivo.isEmpty()) {
            throw new ReglaDeNegocioException("El archivo del avatar es requerido.");
        }
        if (archivo.getSize() > medios.avatarMaxBytes()) {
            throw new ReglaDeNegocioException("El avatar supera el tamaño máximo permitido.");
        }
        String tipo = archivo.getContentType() == null ? "" : archivo.getContentType().toLowerCase(Locale.ROOT);
        if (!medios.tiposAvatar().contains(tipo)) {
            throw new ReglaDeNegocioException("El tipo de archivo no está permitido.");
        }
        String extension = extensionDe(archivo.getOriginalFilename(), tipo);
        byte[] contenido;
        try {
            contenido = archivo.getBytes();
        } catch (java.io.IOException e) {
            throw new ReglaDeNegocioException("No se pudo leer el archivo del avatar.");
        }
        exigirContenidoMinimo(tipo, contenido);
        String ruta = "avatares/" + usuarioId + "." + extension;
        String anterior = usuario.getUrlAvatar();
        String url = almacen.guardar(ruta, tipo, contenido);
        usuario.setUrlAvatar(url);
        if (anterior != null && !anterior.equals(url)) {
            almacen.eliminar(AlmacenObjetos.rutaDesdeUrl(anterior));
        }
        return Dtos.UsuarioRespuesta.de(usuario);
    }

    /** Elimina el objeto del storage y limpia {@code urlAvatar} (R8). */
    @Transactional
    public void eliminarAvatar(UUID usuarioId) {
        Usuario usuario = vigente(usuarioId);
        if (usuario.getUrlAvatar() != null) {
            almacen.eliminar(AlmacenObjetos.rutaDesdeUrl(usuario.getUrlAvatar()));
            usuario.setUrlAvatar(null);
        }
    }

    /**
     * Baja soft-delete (R1): escribe {@code fechaEliminacion} sin destruir filas
     * relacionadas y corta la sesión revocando los refresh (R15).
     */
    @Transactional
    public void darDeBaja(UUID usuarioId) {
        Usuario usuario = vigente(usuarioId);
        usuario.darDeBaja();
        sesiones.revocarTodos(usuarioId);
        verificaciones.findByUsuarioIdAndFechaUsoIsNull(usuarioId)
                .forEach(t -> t.marcarUso(java.time.Instant.now()));
        restablecimientos.findByUsuarioIdAndFechaUsoIsNull(usuarioId)
                .forEach(t -> t.marcarUso(java.time.Instant.now()));
    }

    private Usuario vigente(UUID usuarioId) {
        return usuarios.findById(usuarioId)
                .filter(Usuario::activa)
                .orElseThrow(() -> new NoEncontradoException("La cuenta no existe o fue dada de baja."));
    }

    private static String extensionDe(String original, String tipo) {
        String base = EXTENSION_POR_TIPO.get(tipo);
        if (original != null) {
            String minusculas = original.toLowerCase(Locale.ROOT);
            int punto = minusculas.lastIndexOf('.');
            if (punto >= 0) {
                String ext = minusculas.substring(punto + 1);
                if (EXTENSIONES.contains(ext)) {
                    return ext.equals("jpeg") ? "jpg" : ext;
                }
            }
        }
        return base;
    }

    /** Verificación mínima de contenido: evita guardar texto con content-type de imagen. */
    private static void exigirContenidoMinimo(String tipo, byte[] contenido) {
        if (tipo.equals("image/png") && !empiezaCon(contenido, FIRMA_PNG)
                || tipo.equals("image/jpeg") && !(contenido.length > 2
                        && contenido[0] == (byte) 0xFF && contenido[1] == (byte) 0xD8)
                || tipo.equals("image/gif") && contenido.length < 6) {
            throw new ReglaDeNegocioException("El contenido del archivo no corresponde a su tipo.");
        }
    }

    private static boolean empiezaCon(byte[] contenido, byte[] firma) {
        if (contenido.length < firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (contenido[i] != firma[i]) {
                return false;
            }
        }
        return true;
    }
}
