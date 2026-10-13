package com.unimarket.api.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Cableado del adaptador R2 (N8, D14): se activa solo con {@code app.media.proveedor=r2}
 * y credenciales por entorno. R2 usa la región {@code auto} y endpoint propio por cuenta.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.media.proveedor", havingValue = "r2")
public class ConfiguracionAlmacenR2 {

    @Bean
    S3Client s3Client(PropiedadesMedia propiedades) {
        PropiedadesMedia.R2 r2 = propiedades.r2();
        // Mensaje accionable ANTES de tocar el SDK (con endpoint vacío lanza un NPE genérico).
        AlmacenR2.exigirConfiguracion(r2);
        return S3Client.builder()
                .endpointOverride(URI.create(r2.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.accessKeyId(), r2.secretAccessKey())))
                .build();
    }

    @Bean
    AlmacenObjetos almacenR2(S3Client s3, PropiedadesMedia propiedades) {
        return new AlmacenR2(s3, propiedades.r2());
    }
}
