package com.unimarket.api.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistencia y predicados del anuncio contra PostgreSQL 18 real (PRD §6): visibilidad
 * (R4), ownership (R7), búsqueda con escape literal de comodines y cursores compuestos
 * con desempate por id (D4). También verifica el mapeo jsonb de los arrays pareados (R8)
 * y que los CHECKs/FKs del DBDD aceptan solo filas consistentes (trail de revisión, R5).
 */
@DataJpaTest
@Testcontainers
@DisplayName("Repositorio de Anuncio")
class AnuncioRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private AnuncioRepository anuncios;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private jakarta.persistence.EntityManager em;

    /** Vendedor real: la FK {@code anuncios_vendedor_id_fkey} exige una fila de usuarios. */
    private UUID vendedor(String etiqueta) {
        return jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                etiqueta.toLowerCase() + "-" + UUID.randomUUID() + "@uni.edu",
                "Vendedor " + etiqueta,
                "$2a$10$hashdeficticioparaprueba0000000000000000000000");
    }

    /** Anuncio consistente: si la revisión no es pendiente, el trail va poblado (CHECK). */
    private Anuncio anuncio(UUID vendedorId, UUID revisorId, String nombre, String precio,
                            EstadoAnuncio estado, EstadoRevision revision) {
        Anuncio a = new Anuncio();
        a.setVendedorId(vendedorId);
        a.setNombre(nombre);
        a.setPrecio(new BigDecimal(precio));
        a.setEstado(estado);
        a.setEstadoRevision(revision);
        if (revision != EstadoRevision.pendiente) {
            a.setRevisadoPor(revisorId);
            a.setFechaRevision(Instant.now());
        }
        if (revision == EstadoRevision.rechazado) {
            a.setMotivoRechazo("incumple las normas de publicación");
            a.setOrigenRechazo(OrigenRechazo.rechazo);
        }
        return a;
    }

    @Nested
    @DisplayName("Visibilidad del catálogo (R4)")
    class Visibilidad {

        @Test
        @DisplayName("Solo disponible+aprobado es visible; el resto queda fuera")
        void soloVisible() {
            UUID vendedor = vendedor("visible");
            Anuncio visible = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Visible", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Inactivo", "10", EstadoAnuncio.inactivo, EstadoRevision.aprobado));
            anuncios.saveAndFlush(anuncio(vendedor, null, "Pendiente", "10", EstadoAnuncio.disponible, EstadoRevision.pendiente));
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Vendido", "10", EstadoAnuncio.vendido, EstadoRevision.aprobado));
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Rechazado", "10", EstadoAnuncio.disponible, EstadoRevision.rechazado));
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Eliminado", "10", EstadoAnuncio.eliminado, EstadoRevision.aprobado));

            List<Anuncio> resultado = anuncios.findAll(EspecificacionesAnuncio.visible());

            assertThat(resultado).extracting(Anuncio::getId).containsExactly(visible.getId());
        }
    }

    @Nested
    @DisplayName("Ownership (R7)")
    class Ownership {

        @Test
        @DisplayName("El filtro de vendedor jamás devuelve filas ajenas")
        void soloDelVendedor() {
            UUID ana = vendedor("ana");
            UUID luis = vendedor("luis");
            anuncios.saveAndFlush(anuncio(ana, null, "De Ana", "10", EstadoAnuncio.disponible, EstadoRevision.pendiente));
            anuncios.saveAndFlush(anuncio(luis, null, "De Luis", "10", EstadoAnuncio.disponible, EstadoRevision.pendiente));

            List<Anuncio> deAna = anuncios.findAll(EspecificacionesAnuncio.deVendedor(ana));

            assertThat(deAna).hasSize(1).allMatch(a -> a.getVendedorId().equals(ana));
        }
    }

    @Nested
    @DisplayName("Búsqueda con escape (O3)")
    class Busqueda {

        @Test
        @DisplayName("Un % del usuario es literal, no comodín")
        void porcentajeLiteral() {
            UUID vendedor = vendedor("porcentaje");
            Anuncio conPorcentaje = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Camisa 100% algodón", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Camisa azul", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));

            List<Anuncio> resultado = anuncios.findAll(EspecificacionesAnuncio.busqueda("100%"));

            assertThat(resultado).extracting(Anuncio::getId).containsExactly(conPorcentaje.getId());
        }

        @Test
        @DisplayName("El guion bajo se busca literal sobre nombre y descripción")
        void guionBajoLiteralYDescripcion() {
            UUID vendedor = vendedor("guion");
            Anuncio biciRuta = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Bici_ruta", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Bicicleta normal", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));

            Anuncio conDescripcion = anuncio(vendedor, vendedor, "Otro", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado);
            conDescripcion.setDescripcion("Incluye funda_bici_verde");
            anuncios.saveAndFlush(conDescripcion);

            List<Anuncio> resultado = anuncios.findAll(EspecificacionesAnuncio.busqueda("bici_"));

            assertThat(resultado).extracting(Anuncio::getNombre).containsExactlyInAnyOrder("Bici_ruta", "Otro");
            assertThat(resultado).extracting(Anuncio::getId).contains(biciRuta.getId());
        }
    }

    @Nested
    @DisplayName("Cursores compuestos (D4)")
    class Cursores {

        @Test
        @DisplayName("Con timestamps iguales, el id desempata y ninguna fila se pierde ni repite")
        void cursorFechaEstableConEmpates() {
            UUID vendedor = vendedor("cursor-fecha");
            Instant mismoInstante = Instant.parse("2026-10-12T00:00:00Z");
            Anuncio a = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "A", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            Anuncio b = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "B", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            Anuncio c = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "C", "10", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            for (Anuncio x : List.of(a, b, c)) {
                jdbc.update("UPDATE anuncios SET fecha_creacion = ? WHERE id = ?",
                        java.sql.Timestamp.from(mismoInstante), x.getId());
            }
            // El UPDATE fue por SQL nativo: vaciar el contexto para releer los timestamps forzados.
            em.flush();
            em.clear();

            Sort sort = Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"));
            var primera = anuncios.findAll(EspecificacionesAnuncio.visible(), PageRequest.of(0, 2, sort));
            assertThat(primera.getContent()).hasSize(2);

            Anuncio ultimoDePrimera = primera.getContent().get(1);
            var segunda = anuncios.findAll(
                    EspecificacionesAnuncio.visible().and(EspecificacionesAnuncio.despuesDelCursor(
                            ultimoDePrimera.getFechaCreacion(), ultimoDePrimera.getId())),
                    PageRequest.of(0, 2, sort));

            assertThat(segunda.getContent()).hasSize(1);
            assertThat(List.of(primera.getContent().get(0).getId(), ultimoDePrimera.getId(), segunda.getContent().get(0).getId()))
                    .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        }

        @Test
        @DisplayName("Cursor de precio: tupla (precio, id) estrictamente mayor")
        void cursorPrecio() {
            UUID vendedor = vendedor("cursor-precio");
            anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Barato", "10.00", EstadoAnuncio.disponible, EstadoRevision.aprobado));
            Anuncio caro = anuncios.saveAndFlush(anuncio(vendedor, vendedor, "Caro", "30.00", EstadoAnuncio.disponible, EstadoRevision.aprobado));

            List<Anuncio> resultado = anuncios.findAll(
                    EspecificacionesAnuncio.visible().and(EspecificacionesAnuncio.despuesDelCursorPrecio(
                            new BigDecimal("20.00"), UUID.randomUUID())));

            assertThat(resultado).extracting(Anuncio::getId).containsExactly(caro.getId());
        }
    }

    @Nested
    @DisplayName("Arrays de imágenes (R8)")
    class Imagenes {

        @Test
        @DisplayName("jsonb: urls y paths se persisten pareados y en su orden")
        void arraysPareados() {
            UUID vendedor = vendedor("imagenes");
            Anuncio a = anuncio(vendedor, null, "Con fotos", "10", EstadoAnuncio.disponible, EstadoRevision.pendiente);
            a.setImagenesUrls(List.of("/media/anuncios/x/1.jpg", "/media/anuncios/x/2.png"));
            a.setImagenesPaths(List.of("anuncios/x/1.jpg", "anuncios/x/2.png"));
            Anuncio guardado = anuncios.saveAndFlush(a);
            anuncios.flush();

            Anuncio releido = anuncios.findById(guardado.getId()).orElseThrow();

            assertThat(releido.getImagenesUrls()).containsExactly("/media/anuncios/x/1.jpg", "/media/anuncios/x/2.png");
            assertThat(releido.getImagenesPaths()).containsExactly("anuncios/x/1.jpg", "anuncios/x/2.png");
        }
    }
}
