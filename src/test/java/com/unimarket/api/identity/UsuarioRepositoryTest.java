package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia de {@link Usuario} contra PostgreSQL 18 real (PRD §6: nada en memoria).
 *
 * <p>Cubre R1 (identidad única + soft-delete), R2 (unicidad case-insensitive con índice
 * parcial) y R3 (dominio cerrado de rol con converter).
 */
@DataJpaTest
@Testcontainers
@DisplayName("Repositorio de Usuario")
class UsuarioRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private JdbcTemplate jdbc;

    private static Usuario usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setNombre("Ana Estudiante");
        u.setHashContrasena("$2a$10$hashdeficticioparaprueba0000000000000000000000");
        return u;
    }

    @Nested
    @DisplayName("Registro básico")
    class RegistroBasico {

        @Test
        @DisplayName("Guarda y lee: rol miembro, habilitado y auditoría poblados")
        void guardar_shouldPersistirMiembroHabilitado_cuandoEsNuevo() {
            // Arrange
            Usuario nuevo = usuario("ana@correo.com");

            // Act
            Usuario guardado = usuarios.saveAndFlush(nuevo);

            // Assert
            assertThat(guardado.getId()).isNotNull();
            assertThat(guardado.getRol()).isEqualTo(com.unimarket.api.shared.security.Rol.miembro);
            assertThat(guardado.isHabilitado()).isTrue();
            assertThat(guardado.getFechaCreacion()).isNotNull();
            assertThat(guardado.getFechaActualizacion()).isNotNull();
            assertThat(guardado.getFechaVerificacionEmail()).isNull();
            assertThat(usuarios.findById(guardado.getId())).isPresent();
        }

        @Test
        @DisplayName("El converter persiste el rol como texto del dominio cerrado")
        void guardar_shouldPersistirRolComoTexto_cuandoEsModerador() {
            // Arrange
            Usuario u = usuario("mod@correo.com");
            u.setRol(com.unimarket.api.shared.security.Rol.moderador);

            // Act
            Usuario guardado = usuarios.saveAndFlush(u);

            // Assert
            String rolCrudo = jdbc.queryForObject(
                    "SELECT rol FROM usuarios WHERE id = ?", String.class, guardado.getId());
            assertThat(rolCrudo).isEqualTo("moderador");
            assertThat(usuarios.findById(guardado.getId()).orElseThrow().getRol())
                    .isEqualTo(com.unimarket.api.shared.security.Rol.moderador);
        }
    }

    @Nested
    @DisplayName("Unicidad de email (R2)")
    class UnicidadEmail {

        @Test
        @DisplayName("Email duplicado en distinto caso entre activas viola el índice parcial")
        void guardar_shouldRechazarDuplicadoCaseInsensitive_cuandoActivaExiste() {
            // Arrange
            usuarios.saveAndFlush(usuario("Ana@correo.com"));

            // Act + Assert
            assertThatThrownBy(() -> usuarios.saveAndFlush(usuario("aNA@CORREO.com")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("El email de una cuenta dada de baja queda libre (R1/R2)")
        void guardar_shouldPermitirReutilizacion_cuandoLaAnteriorFueDadaDeBaja() {
            // Arrange
            Usuario anterior = usuarios.saveAndFlush(usuario("libre@correo.com"));
            anterior.darDeBaja();
            usuarios.saveAndFlush(anterior);

            // Act
            Usuario nuevo = usuarios.saveAndFlush(usuario("LIBRE@correo.com"));

            // Assert
            assertThat(nuevo.getId()).isNotEqualTo(anterior.getId());
        }

        @Test
        @DisplayName("La búsqueda por email ignora el caso y excluye dadas de baja")
        void buscar_shouldIgnorarCasoYExcluirBajas() {
            // Arrange
            Usuario activo = usuarios.saveAndFlush(usuario("busca@correo.com"));
            Usuario baja = usuario("otra@correo.com");
            usuarios.saveAndFlush(baja);
            baja.darDeBaja();
            usuarios.saveAndFlush(baja);

            // Act + Assert
            assertThat(usuarios.findByEmailIgnoreCaseAndFechaEliminacionIsNull("BUSCA@CORREO.COM"))
                    .map(Usuario::getId).contains(activo.getId());
            assertThat(usuarios.findByEmailIgnoreCaseAndFechaEliminacionIsNull("OTRA@correo.com"))
                    .isEmpty();
            assertThat(usuarios.existsByEmailIgnoreCaseAndFechaEliminacionIsNull("busca@CORREO.com"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Auditoría por aplicación (DBDD §1: sin triggers)")
    class Auditoria {

        @Test
        @DisplayName("@PreUpdate refresca fechaActualizacion en cada UPDATE")
        void actualizar_shouldRefrescarFechaActualizacion_cuandoCambiaNombre() throws Exception {
            // Arrange
            Usuario u = usuarios.saveAndFlush(usuario("trail@correo.com"));
            var creacion = u.getFechaActualizacion();
            Thread.sleep(10);

            // Act
            u.setNombre("Ana Actualizada");
            Usuario actualizado = usuarios.saveAndFlush(u);

            // Assert
            assertThat(actualizado.getFechaActualizacion()).isAfter(creacion);
            assertThat(actualizado.getFechaCreacion()).isEqualTo(u.getFechaCreacion());
        }
    }
}
