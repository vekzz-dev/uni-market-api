package com.unimarket.api.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador filesystem para dev (D14): cero dependencias, raíz configurable. Resuelve
 * {@code ~} al home del usuario y rechaza cualquier ruta que escape de la raíz
 * (normalización + prefijo). Es el adaptador por defecto ({@code proveedor} ausente o
 * {@code filesystem}); con {@code r2} entra el adaptador S3 (N8).
 */
@Component
@ConditionalOnProperty(name = "app.media.proveedor", havingValue = "filesystem", matchIfMissing = true)
public class AlmacenFilesystem implements AlmacenObjetos {

    private static final Map<String, String> TIPO_POR_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp");

    private final Path raiz;

    public AlmacenFilesystem(PropiedadesMedia propiedades) {
        this.raiz = expandir(propiedades.raiz());
        try {
            Files.createDirectories(raiz);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la raíz de medios: " + raiz, e);
        }
    }

    @Override
    public String guardar(String rutaRelativa, String contentType, byte[] contenido) {
        Path destino = resolver(rutaRelativa);
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, contenido);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el objeto: " + rutaRelativa, e);
        }
        return AlmacenObjetos.urlDesdeRuta(rutaRelativa);
    }

    @Override
    public Optional<Contenido> leer(String rutaRelativa) {
        Path origen;
        try {
            origen = resolver(rutaRelativa);
        } catch (IllegalArgumentException fueraDeRaiz) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(origen)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Contenido(Files.readAllBytes(origen), tipoDe(rutaRelativa)));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el objeto: " + rutaRelativa, e);
        }
    }

    @Override
    public void eliminar(String rutaRelativa) {
        try {
            Files.deleteIfExists(resolver(rutaRelativa));
        } catch (IllegalArgumentException fueraDeRaiz) {
            // Ruta fuera de la raíz: nada que borrar.
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo eliminar el objeto: " + rutaRelativa, e);
        }
    }

    @Override
    public String rutaDesdeUrl(String urlPublica) {
        if (urlPublica != null && urlPublica.startsWith(PREFIJO_PUBLICO)) {
            return urlPublica.substring(PREFIJO_PUBLICO.length());
        }
        throw new IllegalArgumentException("URL pública fuera del almacenamiento: " + urlPublica);
    }

    /** Resuelve la ruta relativa contra la raíz; rechaza el escape con {@code ..}. */
    private Path resolver(String rutaRelativa) {
        Path resuelta = raiz.resolve(rutaRelativa).normalize();
        if (!resuelta.startsWith(raiz)) {
            throw new IllegalArgumentException("Ruta fuera de la raíz de medios: " + rutaRelativa);
        }
        return resuelta;
    }

    private static String tipoDe(String rutaRelativa) {
        String minusculas = rutaRelativa.toLowerCase(Locale.ROOT);
        int punto = minusculas.lastIndexOf('.');
        if (punto >= 0) {
            String tipo = TIPO_POR_EXTENSION.get(minusculas.substring(punto + 1));
            if (tipo != null) {
                return tipo;
            }
        }
        return "application/octet-stream";
    }

    private static Path expandir(String raizCruda) {
        String raiz = raizCruda == null || raizCruda.isBlank() ? "~/.uni-market" : raizCruda.strip();
        if (raiz.startsWith("~")) {
            raiz = System.getProperty("user.home") + raiz.substring(1);
        }
        return Paths.get(raiz).toAbsolutePath().normalize();
    }
}
