package com.unimarket.api.catalog;

import com.unimarket.api.identity.SoportePostgresIT;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Panel del vendedor contra PostgreSQL 18 real: lectura de un anuncio propio por id sin
 * depender de la navegación (#3), filtro por estado de revisión con total filtrado, y
 * separación entre rechazo y retracción (#4) — el chip «Retraídos» necesita distinguir
 * una aprobación revocada de un rechazo de revisión.
 */
@DisplayName("Panel del vendedor — lectura por id, filtros y retracción")
class PanelVendedorIntegrationTest extends SoportePostgresIT {

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
    private EntityManagerFactory emf;

    private record Sesion(UUID id, String token) {
    }

    private Sesion sesion(String rol) {
        String email = "panel-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email, rol) "
                        + "VALUES (?, ?, ?, now(), ?) RETURNING id",
                UUID.class, email, "Ana Pérez", encoder.encode("secreta123"), rol);
        ResponseEntity<String> login = rest.postForEntity(
                "/v1/auth/login", java.util.Map.of("email", email, "contrasena", "secreta123"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Sesion(id, json.readTree(login.getBody()).get("accessToken").asText());
    }

    private static HttpHeaders conSesion(String token) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(token);
        return cabeceras;
    }

    private String crearAnuncio(Sesion vendedor, String nombre) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", nombre);
        partes.add("precio", "42.00");
        partes.add("imagenes", new ByteArrayResource(JPEG) {
            @Override
            public String getFilename() {
                return "foto.jpg";
            }
        });
        HttpHeaders cabeceras = conSesion(vendedor.token());
        cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> creado = rest.postForEntity("/v1/anuncios", new HttpEntity<>(partes, cabeceras), String.class);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(creado.getBody()).get("id").asText();
    }

    private ResponseEntity<String> decidir(Sesion moderador, String anuncioId, String decision, String motivo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(moderador.token());
        String cuerpo = "{\"decision\":\"" + decision + "\",\"motivoRechazo\":\"" + motivo + "\"}";
        return rest.exchange("/v1/moderacion/anuncios/" + anuncioId + "/decisiones", HttpMethod.POST,
                new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private void aprobar(Sesion moderador, String anuncioId) {
        assertThat(decidir(moderador, anuncioId, "aprobar", "ok").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void rechazar(Sesion moderador, String anuncioId) {
        assertThat(decidir(moderador, anuncioId, "rechazar", "no cumple").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void retraer(Sesion moderador, String anuncioId) {
        assertThat(decidir(moderador, anuncioId, "retraer", "se retira").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> panelPorId(Sesion sesion, String anuncioId) {
        return rest.exchange("/v1/mis-anuncios/" + anuncioId, HttpMethod.GET,
                new HttpEntity<>(null, conSesion(sesion.token())), String.class);
    }

    private JsonNode panel(Sesion sesion, String filtro) {
        String uri = filtro == null ? "/v1/mis-anuncios?limite=100" : "/v1/mis-anuncios?limite=100&estadoRevision=" + filtro;
        ResponseEntity<String> respuesta = rest.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(null, conSesion(sesion.token())), String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(respuesta.getBody());
    }

    @Nested
    @DisplayName("Lectura por id (#3)")
    class LecturaPorId {

        @Test
        @DisplayName("El vendedor recupera su anuncio pendiente, que el detalle público oculta con 404")
        void panelPorIdPropio() {
            Sesion vendedor = sesion("miembro");
            String anuncioId = crearAnuncio(vendedor, "Panel id " + UUID.randomUUID());

            ResponseEntity<String> propio = panelPorId(vendedor, anuncioId);

            assertThat(propio.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode cuerpo = json.readTree(propio.getBody());
            assertThat(cuerpo.get("id").asText()).isEqualTo(anuncioId);
            assertThat(cuerpo.get("estadoRevision").asText()).isEqualTo("pendiente");
            assertThat(cuerpo.get("vendedorNombre").asText()).isEqualTo("Ana P.");
            assertThat(rest.getForEntity("/v1/anuncios/" + anuncioId, String.class).getStatusCode())
                    .as("el detalle público sigue ocultando lo no aprobado (R4)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Anuncio ajeno e inexistente responden el MISMO 404 (R7)")
        void ajenoEIndistinguible() {
            Sesion vendedor = sesion("miembro");
            Sesion intruso = sesion("miembro");
            String ajeno = crearAnuncio(vendedor, "Ajeno " + UUID.randomUUID());

            String azar = UUID.randomUUID().toString();
            ResponseEntity<String> respuestaAjena = panelPorId(intruso, ajeno);
            ResponseEntity<String> respuestaAzar = panelPorId(intruso, azar);

            assertThat(respuestaAjena.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(respuestaAzar.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(respuestaAjena.getBody().replace(ajeno, "{id}"))
                    .as("mismo cuerpo: lo único que cambia es el id de la ruta en `instance`")
                    .isEqualTo(respuestaAzar.getBody().replace(azar, "{id}"));
        }

        @Test
        @DisplayName("Sin sesión → 401")
        void sinSesion() {
            ResponseEntity<String> respuesta = rest.getForEntity("/v1/mis-anuncios/" + UUID.randomUUID(), String.class);

            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Filtro por estado de revisión (#3)")
    class FiltroPorEstado {

        @Test
        @DisplayName("Cada valor devuelve solo sus filas y el total refleja el filtro")
        void filtraPorEstado() {
            Sesion vendedor = sesion("miembro");
            Sesion moderador = sesion("moderador");
            String pendiente = crearAnuncio(vendedor, "P " + UUID.randomUUID());
            String aprobado = crearAnuncio(vendedor, "A " + UUID.randomUUID());
            String rechazado = crearAnuncio(vendedor, "R " + UUID.randomUUID());
            aprobar(moderador, aprobado);
            rechazar(moderador, rechazado);

            assertThat(ids(panel(vendedor, "pendiente"))).containsExactly(pendiente);
            assertThat(ids(panel(vendedor, "aprobado"))).containsExactly(aprobado);
            assertThat(ids(panel(vendedor, "rechazado"))).containsExactly(rechazado);
            assertThat(panel(vendedor, "pendiente").get("paginacion").get("total").asLong()).isEqualTo(1);
            assertThat(ids(panel(vendedor, null)))
                    .as("sin parámetro el panel sigue devolviendo todo")
                    .containsExactlyInAnyOrder(pendiente, aprobado, rechazado);
        }

        @Test
        @DisplayName("Una página filtrada cuesta UNA consulta de nombres, no una por anuncio")
        void unaConsultaDeNombresPorPagina() {
            Sesion vendedor = sesion("miembro");
            for (int i = 0; i < 3; i++) {
                crearAnuncio(vendedor, "Coste " + i + " " + UUID.randomUUID());
            }
            Statistics estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
            estadisticas.setStatisticsEnabled(true);
            estadisticas.clear();

            JsonNode datos = panel(vendedor, "pendiente").get("datos");

            assertThat(datos).hasSize(3);
            long consultasDeUsuarios = java.util.Arrays.stream(estadisticas.getQueries())
                    .filter(consulta -> consulta.contains("from usuarios"))
                    .count();
            assertThat(consultasDeUsuarios)
                    .as("el mapeador resuelve la página en lote: %s",
                            java.util.Arrays.toString(estadisticas.getQueries()))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Un valor fuera del dominio responde 400, no 500")
        void valorInvalido() {
            Sesion vendedor = sesion("miembro");

            ResponseEntity<String> respuesta = rest.exchange("/v1/mis-anuncios?estadoRevision=inventado",
                    HttpMethod.GET, new HttpEntity<>(null, conSesion(vendedor.token())), String.class);

            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private static java.util.List<String> ids(JsonNode panel) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        panel.get("datos").forEach(fila -> ids.add(fila.get("id").asText()));
        return ids;
    }
}
