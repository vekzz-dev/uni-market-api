package com.unimarket.api.media;

import java.util.Optional;

/**
 * Puerto de almacenamiento de objetos (D14, R8): la API solo expone URLs públicas
 * ({@code /media/**}); las claves internas jamás salen al wire.
 */
public interface AlmacenObjetos {

    /** Prefijo público bajo el que se sirven los objetos. */
    String PREFIJO_PUBLICO = "/media/";

    /** Contenido leído: bytes y tipo para la respuesta HTTP. */
    record Contenido(byte[] bytes, String contentType) {
    }

    /**
     * Guarda el objeto en la ruta relativa y devuelve su URL pública.
     *
     * @param rutaRelativa p. ej. {@code avatares/{uuid}.png}
     * @param contentType  tipo validado por el caso de uso
     * @param contenido    bytes del objeto
     */
    String guardar(String rutaRelativa, String contentType, byte[] contenido);

    /** Lee el objeto por su ruta relativa, vacío si no existe. */
    Optional<Contenido> leer(String rutaRelativa);

    /** Elimina el objeto si existe; inexistente no es error. */
    void eliminar(String rutaRelativa);

    /**
     * Ruta relativa a partir de la URL pública (inversa de la construcción del adaptador).
     * Es de INSTANCIA porque cada adaptador conoce su forma de URL: filesystem extrae tras
     * {@code /media/} y R2 tras su URL pública base (N8, D14).
     *
     * @throws IllegalArgumentException si la URL no pertenece a este almacenamiento
     */
    String rutaDesdeUrl(String urlPublica);

    /** URL pública estilo API para una ruta relativa (filesystem y tests). */
    static String urlDesdeRuta(String rutaRelativa) {
        return PREFIJO_PUBLICO + rutaRelativa;
    }
}
