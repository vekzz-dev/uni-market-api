package com.unimarket.api.catalog;

import com.unimarket.api.identity.SoportePostgresIT;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sondas de regresión de R17 (issue #2) contra PostgreSQL real: forma EXACTA del wire —el
 * contrato es la fuente de verdad y un campo de más el cliente generado lo descarta en
 * silencio— y el coste de la resolución en lote, medido con las estadísticas de Hibernate
 * para que un futuro mapeo por anuncio rompa el build en vez de degradar el catálogo.
 */
@DisplayName("R17 — nombre público del vendedor (regresión de wire y coste)")
class NombrePublicoVendedorIntegrationTest extends SoportePostgresIT {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private AnuncioRepository anuncios;

    @Autowired
    private EntityManagerFactory emf;

    private record Sesion(UUID id, String token) {
    }

    private Sesion sesion(String nombre) {
        String email = "r17-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email) "
                        + "VALUES (?, ?, ?, now()) RETURNING id",
                UUID.class, email, nombre, encoder.encode("secreta123"));
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> login = rest.postForEntity("/v1/auth/login",
                new HttpEntity<>(Map.of("email", email, "contrasena", "secreta123"), cabeceras), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Sesion(id, json.readTree(login.getBody()).get("accessToken").asText());
    }

    private UUID crear(Sesion sesion, String nombre) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", nombre);
        partes.add("precio", "10.00");
        partes.add("imagenes", new ByteArrayResource(JPEG) {
            @Override
            public String getFilename() {
                return "foto.jpg";
            }
        });
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(sesion.token());
        cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> creado = rest.postForEntity("/v1/anuncios", new HttpEntity<>(partes, cabeceras), String.class);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json.readTree(creado.getBody()).get("id").asText());
    }

    private void aprobar(UUID anuncioId, UUID revisorId) {
        Anuncio anuncio = anuncios.findById(anuncioId).orElseThrow();
        anuncio.setEstadoRevision(EstadoRevision.aprobado);
        anuncio.setRevisadoPor(revisorId);
        anuncio.setFechaRevision(Instant.now());
        anuncios.saveAndFlush(anuncio);
    }

    private JsonNode pagina(String etiqueta) {
        return json.readTree(rest.getForEntity("/v1/anuncios?q=" + etiqueta + "&limite=100", String.class).getBody());
    }

    @Test
    @DisplayName("El listado publica 9 campos y el detalle 11, con el nombre público y sin apellidos")
    void wireExactoYNombrePublico() {
        Sesion vendedora = sesion("Ana María Pérez Gómez");
        String etiqueta = "R17-wire-" + UUID.randomUUID();
        UUID anuncio = crear(vendedora, etiqueta);
        aprobar(anuncio, vendedora.id());

        JsonNode fila = pagina(etiqueta).get("datos").get(0);
        assertThat(fila.propertyNames()).containsExactlyInAnyOrder(
                "id", "nombre", "precio", "descripcion", "categoriaId", "imagenUrls",
                "vendedorId", "vendedorNombre", "fechaCreacion");
        assertThat(fila.get("vendedorNombre").asText()).isEqualTo("Ana G.");

        ResponseEntity<String> detalle = rest.getForEntity("/v1/anuncios/" + anuncio, String.class);
        JsonNode cuerpo = json.readTree(detalle.getBody());
        assertThat(cuerpo.propertyNames()).containsExactlyInAnyOrder(
                "id", "nombre", "precio", "descripcion", "categoriaId", "imagenUrls",
                "vendedorId", "vendedorNombre", "fechaCreacion", "estado", "fechaActualizacion");
        assertThat(cuerpo.get("vendedorNombre").asText()).isEqualTo("Ana G.");
        assertThat(detalle.getBody()).doesNotContain("Pérez").doesNotContain("Gómez").doesNotContain("María");
    }

    @Test
    @DisplayName("Una página con 3 anuncios de 2 vendedores cuesta UNA consulta a usuarios (sin N+1)")
    void unaConsultaPorPagina() {
        Sesion ana = sesion("Ana Pérez");
        Sesion luis = sesion("Luis Gómez");
        String etiqueta = "R17-lote-" + UUID.randomUUID();
        aprobar(crear(ana, etiqueta + "-1"), ana.id());
        aprobar(crear(ana, etiqueta + "-2"), ana.id());
        aprobar(crear(luis, etiqueta + "-3"), ana.id());

        Statistics estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
        estadisticas.setStatisticsEnabled(true);
        estadisticas.clear();

        JsonNode datos = pagina(etiqueta).get("datos");

        assertThat(datos).hasSize(3);
        long consultasDeUsuarios = java.util.Arrays.stream(estadisticas.getQueries())
                .filter(consulta -> consulta.contains("from usuarios"))
                .count();
        assertThat(consultasDeUsuarios)
                .as("una consulta por página, nunca una por anuncio: %s", java.util.Arrays.toString(estadisticas.getQueries()))
                .isEqualTo(1);
    }
}
