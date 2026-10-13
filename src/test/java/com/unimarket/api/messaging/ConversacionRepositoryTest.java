package com.unimarket.api.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia de mensajería contra PostgreSQL 18 real (PRD §6): dedupe por par (R9),
 * CHECK de auto-conversación, participación (R10), no leídos con marcado explícito y
 * cursor estable con timestamps iguales (D4).
 */
@DataJpaTest
@Testcontainers
@DisplayName("Repositorio de mensajería")
class ConversacionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private ConversacionRepository conversaciones;

    @Autowired
    private MensajeRepository mensajes;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private jakarta.persistence.EntityManager em;

    private UUID usuario(String etiqueta) {
        return jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                etiqueta.toLowerCase() + "-" + UUID.randomUUID() + "@uni.edu",
                "Usuario " + etiqueta,
                "$2a$10$hashdeficticioparaprueba0000000000000000000000");
    }

    private UUID anuncio(UUID vendedor) {
        return jdbc.queryForObject(
                "INSERT INTO anuncios (nombre, precio, vendedor_id, estado_revision, revisado_por, fecha_revision) "
                        + "VALUES ('Item', 10.00, ?, 'aprobado', ?, now()) RETURNING id",
                UUID.class, vendedor, vendedor);
    }

    private Conversacion conversacion(UUID anuncioId, UUID comprador, UUID vendedor) {
        return conversaciones.saveAndFlush(new Conversacion(anuncioId, comprador, vendedor));
    }

    @Nested
    @DisplayName("Dedupe y CHECK (R9)")
    class Dedupe {

        @Test
        @DisplayName("Una sola conversación por par (anuncio, comprador): la UNIQUE lo impone")
        void parUnico() {
            UUID vendedor = usuario("vendedor-par");
            UUID comprador = usuario("comprador-par");
            UUID anuncioId = anuncio(vendedor);
            conversacion(anuncioId, comprador, vendedor);

            assertThat(conversaciones.findByAnuncioIdAndCompradorId(anuncioId, comprador)).isPresent();
            assertThatThrownBy(() -> conversaciones.saveAndFlush(new Conversacion(anuncioId, comprador, vendedor)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Auto-conversación rechazada por el CHECK (comprador = vendedor)")
        void sinAutoConversacion() {
            UUID usuario = usuario("dual");
            UUID anuncioId = anuncio(usuario);

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO conversaciones (anuncio_id, comprador_id, vendedor_id) VALUES (?, ?, ?)",
                    anuncioId, usuario, usuario))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("Participación y no leídos (R10)")
    class Participacion {

        @Test
        @DisplayName("El filtro de participante cubre ambos roles y excluye terceros")
        void deParticipante() {
            UUID vendedor = usuario("vendedor-part");
            UUID comprador = usuario("comprador-part");
            UUID tercero = usuario("tercero-part");
            UUID anuncioId = anuncio(vendedor);
            Conversacion conversacion = conversacion(anuncioId, comprador, vendedor);

            assertThat(conversaciones.findAll(EspecificacionesMensajeria.deParticipante(comprador)))
                    .extracting(Conversacion::getId).containsExactly(conversacion.getId());
            assertThat(conversaciones.findAll(EspecificacionesMensajeria.deParticipante(vendedor)))
                    .extracting(Conversacion::getId).containsExactly(conversacion.getId());
            assertThat(conversaciones.findAll(EspecificacionesMensajeria.deParticipante(tercero))).isEmpty();
        }

        @Test
        @DisplayName("No leídos cuenta solo recibidos; el marcado es explícito e idempotente")
        void noLeidosYMarcado() {
            UUID vendedor = usuario("vendedor-leido");
            UUID comprador = usuario("comprador-leido");
            Conversacion conversacion = conversacion(anuncio(vendedor), comprador, vendedor);
            mensajes.saveAndFlush(new Mensaje(conversacion.getId(), comprador, "hola 1"));
            mensajes.saveAndFlush(new Mensaje(conversacion.getId(), comprador, "hola 2"));
            mensajes.saveAndFlush(new Mensaje(conversacion.getId(), vendedor, "respuesta"));

            assertThat(mensajes.countByConversacionIdAndLeidoFalseAndRemitenteIdNot(conversacion.getId(), vendedor))
                    .as("dos recibidos sin leer").isEqualTo(2);
            assertThat(mensajes.countByConversacionIdAndLeidoFalseAndRemitenteIdNot(conversacion.getId(), comprador))
                    .as("el propio enviado jamás cuenta").isEqualTo(1);

            assertThat(mensajes.marcarLeidos(conversacion.getId(), vendedor)).isEqualTo(2);
            assertThat(mensajes.marcarLeidos(conversacion.getId(), vendedor)).as("idempotente").isZero();
            assertThat(mensajes.countByConversacionIdAndLeidoFalseAndRemitenteIdNot(conversacion.getId(), vendedor))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Cursor del historial (D4)")
    class Cursor {

        @Test
        @DisplayName("Con timestamps iguales el id desempata: dos páginas cubren las tres filas")
        void cursorEstable() {
            UUID vendedor = usuario("vendedor-cursor");
            UUID comprador = usuario("comprador-cursor");
            Conversacion conversacion = conversacion(anuncio(vendedor), comprador, vendedor);
            Mensaje a = mensajes.saveAndFlush(new Mensaje(conversacion.getId(), comprador, "a"));
            Mensaje b = mensajes.saveAndFlush(new Mensaje(conversacion.getId(), comprador, "b"));
            Mensaje c = mensajes.saveAndFlush(new Mensaje(conversacion.getId(), comprador, "c"));
            Instant mismo = Instant.parse("2026-10-12T00:00:00Z");
            for (Mensaje m : List.of(a, b, c)) {
                jdbc.update("UPDATE mensajes SET fecha_creacion = ? WHERE id = ?",
                        Timestamp.from(mismo), m.getId());
            }
            em.flush();
            em.clear();

            Sort sort = Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"));
            var primera = mensajes.findAll(EspecificacionesMensajeria.deConversacion(conversacion.getId()),
                    PageRequest.of(0, 2, sort));
            assertThat(primera.getContent()).hasSize(2);
            Mensaje ultimo = primera.getContent().get(1);
            var segunda = mensajes.findAll(
                    EspecificacionesMensajeria.deConversacion(conversacion.getId())
                            .and(EspecificacionesMensajeria.antesDelCursor(ultimo.getFechaCreacion(), ultimo.getId())),
                    PageRequest.of(0, 2, sort));

            assertThat(segunda.getContent()).hasSize(1);
            assertThat(List.of(primera.getContent().get(0).getId(), ultimo.getId(), segunda.getContent().get(0).getId()))
                    .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        }
    }
}
