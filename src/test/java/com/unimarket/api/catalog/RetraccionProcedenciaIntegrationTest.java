package com.unimarket.api.catalog;

import com.unimarket.api.identity.SoportePostgresIT;
import org.junit.jupiter.api.DisplayName;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Procedencia de la retracción (issue #4) contra PostgreSQL 18 real: una aprobación
 * revocada y un rechazo de revisión comparten {@code estadoRevision='rechazado'}, así que
 * solo {@code origen_rechazo} los separa. Sin ese dato el chip «Retraídos» no es
 * representable y el vendedor no puede saber si su anuncio se rechazó o se retiró.
 */
@DisplayName("Retracción — procedencia del rechazo y chip Retraídos")
class RetraccionProcedenciaIntegrationTest extends SoportePostgresIT {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PasswordEncoder encoder;

    private record Sesion(UUID id, String token) {
    }

    private Sesion sesion(String rol) {
        String email = "retraccion-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email, rol) "
                        + "VALUES (?, ?, ?, now(), ?) RETURNING id",
                UUID.class, email, "Ana Pérez", encoder.encode("secreta123"), rol);
        ResponseEntity<String> login = rest.postForEntity(
                "/v1/auth/login", Map.of("email", email, "contrasena", "secreta123"), String.class);
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

    private void decidir(Sesion moderador, String anuncioId, String decision) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(moderador.token());
        ResponseEntity<String> respuesta = rest.exchange(
                "/v1/moderacion/anuncios/" + anuncioId + "/decisiones", HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"" + decision + "\",\"motivoRechazo\":\"motivo\"}", cabeceras),
                String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode panel(Sesion vendedor, String filtro) {
        ResponseEntity<String> respuesta = rest.exchange(
                "/v1/mis-anuncios?limite=100&estadoRevision=" + filtro, HttpMethod.GET,
                new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(respuesta.getBody());
    }

    private JsonNode panelPorId(Sesion vendedor, String anuncioId) {
        ResponseEntity<String> respuesta = rest.exchange("/v1/mis-anuncios/" + anuncioId, HttpMethod.GET,
                new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(respuesta.getBody());
    }

    private static List<String> ids(JsonNode panel) {
        List<String> ids = new ArrayList<>();
        panel.get("datos").forEach(fila -> ids.add(fila.get("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("Retraer saca el anuncio de Rechazados y lo deja en Retraídos")
    void retraidoNoEsRechazado() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        String retraido = crearAnuncio(vendedor, "Retraído " + UUID.randomUUID());
        String rechazado = crearAnuncio(vendedor, "Rechazado " + UUID.randomUUID());
        decidir(moderador, retraido, "aprobar");
        decidir(moderador, retraido, "retraer");
        decidir(moderador, rechazado, "rechazar");

        assertThat(ids(panel(vendedor, "retraido"))).containsExactly(retraido);
        assertThat(ids(panel(vendedor, "rechazado"))).containsExactly(rechazado);
    }

    @Test
    @DisplayName("El panel por id expone retraido=true solo cuando la aprobación se revocó")
    void retraidoEnElPanel() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        String retraido = crearAnuncio(vendedor, "Revocado " + UUID.randomUUID());
        String rechazado = crearAnuncio(vendedor, "Rechazado " + UUID.randomUUID());
        decidir(moderador, retraido, "aprobar");
        decidir(moderador, retraido, "retraer");
        decidir(moderador, rechazado, "rechazar");

        assertThat(panelPorId(vendedor, retraido).get("retraido").asBoolean()).isTrue();
        assertThat(panelPorId(vendedor, rechazado).get("retraido").asBoolean()).isFalse();
        assertThat(panelPorId(vendedor, retraido).get("estadoRevision").asText())
                .as("el estado publicado sigue siendo rechazado: la procedencia no cambia el enum (R5)")
                .isEqualTo("rechazado");
    }
}
