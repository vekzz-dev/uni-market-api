package com.unimarket.api.identity;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base de integración contra PostgreSQL 18 real (PRD §6: nada en memoria, SDD §9).
 *
 * <p>Un único contenedor singleton compartido por todas las subclases: el contexto Spring
 * se cachea una vez y Flyway V1–V3 corre una sola vez. Cada test aísla sus datos con
 * emails únicos (sufijo UUID).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class SoportePostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    static final Path MEDIA_DIR;

    static {
        try {
            MEDIA_DIR = Files.createTempDirectory("uni-market-test-media");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        POSTGRES.start();
    }

    /** Cablea el DataSource al contenedor y fija valores de test (sin secretos reales). */
    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("app.seguridad.jwt.secreto",
                () -> "test-secreto-local-solo-para-tests-0123456789abcdef");
        registro.add("app.media.raiz", () -> MEDIA_DIR.toString());
    }
}
