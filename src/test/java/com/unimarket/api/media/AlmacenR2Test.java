package com.unimarket.api.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adaptador R2 (N8) con el cliente S3 mockeado: sin red. Cubre el mapeo bucket/clave/tipo
 * en las llamadas, la construcción/parseo de URLs públicas y el mapeo del objeto ausente.
 * La verificación contra R2 real es de despliegue (documentada en el SDD).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlmacenR2")
class AlmacenR2Test {

    private static final byte[] BYTES = {1, 2, 3};

    @Mock
    private S3Client s3;

    private final PropiedadesMedia.R2 configuracion = new PropiedadesMedia.R2(
            "https://cuenta.r2.cloudflarestorage.com", "unimarket", "clave", "secreto",
            "https://pub-abc.r2.dev/");

    private AlmacenR2 almacen() {
        return new AlmacenR2(s3, configuracion);
    }

    @Test
    @DisplayName("guardar sube con bucket/clave/contentType y devuelve la URL pública")
    void guardar() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String url = almacen().guardar("anuncios/v1/1.jpg", "image/jpeg", BYTES);

        assertThat(url).isEqualTo("https://pub-abc.r2.dev/anuncios/v1/1.jpg");
        ArgumentCaptor<PutObjectRequest> captura = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captura.capture(), any(RequestBody.class));
        assertThat(captura.getValue().bucket()).isEqualTo("unimarket");
        assertThat(captura.getValue().key()).isEqualTo("anuncios/v1/1.jpg");
        assertThat(captura.getValue().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("leer devuelve bytes y contentType; la clave ausente es Optional.empty")
    void leer() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().contentType("image/png").build(), BYTES));

        Optional<AlmacenObjetos.Contenido> presente = almacen().leer("anuncios/v1/1.png");

        assertThat(presente).isPresent();
        assertThat(presente.get().bytes()).isEqualTo(BYTES);
        assertThat(presente.get().contentType()).isEqualTo("image/png");

        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no existe").build());
        assertThat(almacen().leer("anuncios/v1/fantasma.png")).isEmpty();
    }

    @Test
    @DisplayName("eliminar borra por bucket/clave (idempotente por semántica S3)")
    void eliminar() {
        almacen().eliminar("anuncios/v1/1.jpg");

        ArgumentCaptor<DeleteObjectRequest> captura = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captura.capture());
        assertThat(captura.getValue().bucket()).isEqualTo("unimarket");
        assertThat(captura.getValue().key()).isEqualTo("anuncios/v1/1.jpg");
    }

    @Test
    @DisplayName("rutaDesdeUrl: inversa exacta y rechazo de URLs ajenas")
    void rutaDesdeUrl() {
        assertThat(almacen().rutaDesdeUrl("https://pub-abc.r2.dev/anuncios/v1/1.jpg"))
                .isEqualTo("anuncios/v1/1.jpg");
        assertThatThrownBy(() -> almacen().rutaDesdeUrl("https://otro-cdn.example.com/x.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> almacen().rutaDesdeUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Configuración incompleta → IllegalStateException con qué falta")
    void configuracionIncompleta() {
        assertThatThrownBy(() -> new AlmacenR2(s3,
                new PropiedadesMedia.R2("https://x", "b", "k", null, "https://pub.r2.dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEDIA_R2_SECRET_ACCESS_KEY");
        assertThatThrownBy(() -> new AlmacenR2(s3, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
