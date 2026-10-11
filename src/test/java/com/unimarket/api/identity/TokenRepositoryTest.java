package com.unimarket.api.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia de los 3 tokens de identidad (R11/R12): hash UNIQUE, expiración obligatoria,
 * un solo uso y familia de rotación del refresh.
 */
@DataJpaTest
@Testcontainers
@DisplayName("Repositorios de tokens de identidad")
class TokenRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private TokenVerificacionEmailRepository verificaciones;

    @Autowired
    private TokenRestablecimientoRepository restablecimientos;

    @Autowired
    private TokenRefreshRepository refreshes;

    private UUID usuarioId;

    @BeforeEach
    void crearUsuario() {
        Usuario u = new Usuario();
        u.setEmail("token@correo.com");
        u.setNombre("Toki");
        u.setHashContrasena("$2a$10$hashdeficticioparaprueba0000000000000000000000");
        usuarioId = usuarios.saveAndFlush(u).getId();
    }

    private static TokenVerificacionEmail verificacion(UUID usuarioId, String hash) {
        TokenVerificacionEmail t = new TokenVerificacionEmail();
        t.setUsuarioId(usuarioId);
        t.setHashToken(hash);
        t.setFechaExpiracion(Instant.now().plus(24, ChronoUnit.HOURS));
        return t;
    }

    @Nested
    @DisplayName("Verificación de email (R11: 24 h, un solo uso)")
    class VerificacionEmail {

        @Test
        @DisplayName("Guarda y resuelve por hash del valor presentado")
        void guardar_shouldResolverPorHash() {
            // Arrange
            verificaciones.saveAndFlush(verificacion(usuarioId, "hash-abc"));

            // Act
            var encontrado = verificaciones.findByHashToken("hash-abc");

            // Assert
            assertThat(encontrado).isPresent();
            assertThat(encontrado.orElseThrow().getFechaUso()).isNull();
        }

        @Test
        @DisplayName("El hash es UNIQUE: el mismo token no se persiste dos veces")
        void guardar_shouldRechazarHashDuplicado() {
            // Arrange
            verificaciones.saveAndFlush(verificacion(usuarioId, "hash-dup"));

            // Act + Assert
            TokenVerificacionEmail otro = verificacion(UUID.randomUUID(), "hash-dup");
            assertThatThrownBy(() -> verificaciones.saveAndFlush(otro))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Activos por usuario: el consumido (fechaUso) ya no cuenta")
        void activos_shouldExcluirConsumidos() {
            // Arrange
            TokenVerificacionEmail activo = verificaciones.saveAndFlush(verificacion(usuarioId, "h1"));
            TokenVerificacionEmail usado = verificacion(usuarioId, "h2");
            usado.setFechaUso(Instant.now());
            verificaciones.saveAndFlush(usado);

            // Act
            var activos = verificaciones.findByUsuarioIdAndFechaUsoIsNull(usuarioId);

            // Assert
            assertThat(activos).extracting(TokenVerificacionEmail::getHashToken).containsExactly("h1");
            assertThat(activo.activo(Instant.now())).isTrue();
            assertThat(usado.activo(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("Expirado ya no está activo aunque no se haya usado")
        void activo_shouldSerFalso_cuandoExpiro() {
            // Arrange
            TokenVerificacionEmail t = verificacion(usuarioId, "h-exp");
            t.setFechaExpiracion(Instant.now().minus(1, ChronoUnit.MINUTES));

            // Act + Assert
            assertThat(t.activo(Instant.now())).isFalse();
        }
    }

    @Nested
    @DisplayName("Restablecimiento (R11: 60 min, un solo uso)")
    class Restablecimiento {

        @Test
        @DisplayName("Guarda con expiración obligatoria y resuelve por hash")
        void guardar_shouldResolverPorHash_conExpiracion() {
            // Arrange
            TokenRestablecimientoContrasena t = new TokenRestablecimientoContrasena();
            t.setUsuarioId(usuarioId);
            t.setHashToken("hash-reset-1");
            t.setFechaExpiracion(Instant.now().plus(60, ChronoUnit.MINUTES));

            // Act
            restablecimientos.saveAndFlush(t);

            // Assert
            assertThat(restablecimientos.findByHashToken("hash-reset-1")).isPresent();
            assertThat(restablecimientos.findByUsuarioIdAndFechaUsoIsNull(usuarioId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Refresh rotativo (R12: familia + revocación)")
    class Refresh {

        @Test
        @DisplayName("Rotación: anterior marcado usado, nuevo en la misma familia")
        void rotar_shouldMantenerFamilia_conAnteriorUsado() {
            // Arrange
            UUID familia = UUID.randomUUID();
            TokenRefresh anterior = new TokenRefresh();
            anterior.setUsuarioId(usuarioId);
            anterior.setHashToken("hash-viejo");
            anterior.setFechaExpiracion(Instant.now().plus(30, ChronoUnit.DAYS));
            anterior.setFamiliaId(familia);
            refreshes.saveAndFlush(anterior);

            // Act: la rotación marca uso e inserta el nuevo en la misma transacción de servicio;
            // aquí se verifica el invariante a nivel de repositorio.
            anterior.setFechaUso(Instant.now());
            refreshes.saveAndFlush(anterior);
            TokenRefresh nuevo = new TokenRefresh();
            nuevo.setUsuarioId(usuarioId);
            nuevo.setHashToken("hash-nuevo");
            nuevo.setFechaExpiracion(Instant.now().plus(30, ChronoUnit.DAYS));
            nuevo.setFamiliaId(familia);
            refreshes.saveAndFlush(nuevo);

            // Assert
            assertThat(refreshes.findByFamiliaId(familia)).hasSize(2);
            assertThat(refreshes.findByUsuarioIdAndFechaUsoIsNull(usuarioId))
                    .extracting(TokenRefresh::getHashToken).containsExactly("hash-nuevo");
        }

        @Test
        @DisplayName("Revocación de familia: todos los miembros quedan revocados")
        void revocar_shouldMarcarTodaLaFamilia() {
            // Arrange: r1 rotado (usado) y r2 activo, misma familia.
            UUID familia = UUID.randomUUID();
            TokenRefresh r1 = new TokenRefresh();
            r1.setUsuarioId(usuarioId);
            r1.setHashToken("r1");
            r1.setFechaExpiracion(Instant.now().plus(30, ChronoUnit.DAYS));
            r1.setFamiliaId(familia);
            r1.setFechaUso(Instant.now());
            refreshes.saveAndFlush(r1);
            TokenRefresh r2 = new TokenRefresh();
            r2.setUsuarioId(usuarioId);
            r2.setHashToken("r2");
            r2.setFechaExpiracion(Instant.now().plus(30, ChronoUnit.DAYS));
            r2.setFamiliaId(familia);
            refreshes.saveAndFlush(r2);

            // Act
            Instant ahora = Instant.now();
            refreshes.findByFamiliaId(familia).forEach(t -> {
                t.setFechaUso(ahora);
                t.setFechaRevocacion(ahora);
            });
            refreshes.flush();

            // Assert
            assertThat(refreshes.findByFamiliaId(familia))
                    .allMatch(t -> t.getFechaRevocacion() != null);
            assertThat(refreshes.findByUsuarioIdAndFechaUsoIsNull(usuarioId)).isEmpty();
        }
    }
}
