package com.unimarket.api.media;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * Adaptador Cloudflare R2 para producción (N8, D14): API S3-compatible con el SDK v2 de
 * AWS. Las URLs públicas son {@code urlPublica/{clave}} (r2.dev o dominio propio); las
 * claves internas siguen sin salir al wire salvo como parte de la URL pública, igual que
 * en el adaptador de filesystem.
 */
public class AlmacenR2 implements AlmacenObjetos {

    private final S3Client s3;
    private final String bucket;
    private final String urlPublica;

    public AlmacenR2(S3Client s3, PropiedadesMedia.R2 r2) {
        exigirConfiguracion(r2);
        this.s3 = s3;
        this.bucket = r2.bucket();
        this.urlPublica = r2.urlPublica().endsWith("/")
                ? r2.urlPublica().substring(0, r2.urlPublica().length() - 1)
                : r2.urlPublica();
    }

    /**
     * Validación compartida con el cableado del `S3Client` (que también debe fallar con
     * mensaje accionable y no con el NPE genérico del SDK cuando el endpoint está vacío).
     */
    static void exigirConfiguracion(PropiedadesMedia.R2 r2) {
        if (r2 == null
                || vacio(r2.endpoint()) || vacio(r2.bucket())
                || vacio(r2.accessKeyId()) || vacio(r2.secretAccessKey()) || vacio(r2.urlPublica())) {
            throw new IllegalStateException(
                    "Proveedor r2 sin configurar: define MEDIA_R2_ENDPOINT, MEDIA_R2_BUCKET, "
                            + "MEDIA_R2_ACCESS_KEY_ID, MEDIA_R2_SECRET_ACCESS_KEY y MEDIA_R2_URL_PUBLICA.");
        }
    }

    @Override
    public String guardar(String rutaRelativa, String contentType, byte[] contenido) {
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(rutaRelativa).contentType(contentType).build(),
                RequestBody.fromBytes(contenido));
        return urlPublica + "/" + rutaRelativa;
    }

    @Override
    public Optional<Contenido> leer(String rutaRelativa) {
        try {
            ResponseBytes<GetObjectResponse> respuesta = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(rutaRelativa).build());
            return Optional.of(new Contenido(respuesta.asByteArray(), respuesta.response().contentType()));
        } catch (NoSuchKeyException inexistente) {
            return Optional.empty();
        }
    }

    @Override
    public void eliminar(String rutaRelativa) {
        // S3 elimina de forma idempotente: borrar algo inexistente no es error.
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(rutaRelativa).build());
    }

    @Override
    public String rutaDesdeUrl(String urlPublicaObjeto) {
        String prefijo = urlPublica + "/";
        if (urlPublicaObjeto != null && urlPublicaObjeto.startsWith(prefijo)) {
            return urlPublicaObjeto.substring(prefijo.length());
        }
        throw new IllegalArgumentException("URL pública fuera del almacenamiento: " + urlPublicaObjeto);
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
