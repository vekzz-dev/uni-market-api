package com.unimarket.api.messaging;

import com.unimarket.api.identity.SoportePostgresIT;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mensajería end-to-end contra PostgreSQL 18 real (PRD §6): el comprador abre sobre un
 * anuncio aprobado POR LA API de moderación, dedupe 201→200, envío, no leídos explícitos,
 * panel ordenado, historial con cursor y todos los guards de R9/R10/R16.
 */
@DisplayName("Mensajería — flujo HTTP completo")
class MensajeriaFlujoIntegrationTest extends SoportePostgresIT {

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

    private Sesion usuarioConSesion(String etiqueta, String rol) {
        String email = etiqueta.toLowerCase() + "-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email, rol) "
                        + "VALUES (?, ?, ?, now(), ?) RETURNING id",
                UUID.class, email, "Usuario " + etiqueta, encoder.encode("secreta123"), rol);
        ResponseEntity<String> login = rest.postForEntity(
                "/v1/auth/login", Map.of("email", email, "contrasena", "secreta123"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Sesion(id, json.readTree(login.getBody()).get("accessToken").asText());
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private static HttpHeaders conSesion(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    /** Publica un anuncio y lo aprueba por la API real de moderación (sin trucos de BD). */
    private String anuncioAprobado(Sesion vendedor, Sesion moderador, String nombre) {
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
        String id = json.readTree(creado.getBody()).get("id").asText();
        ResponseEntity<String> decision = rest.exchange("/v1/moderacion/anuncios/" + id + "/decisiones", HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"aprobar\"}", jsonHeaders(moderador.token())), String.class);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private ResponseEntity<String> abrir(String token, String anuncioId) {
        return rest.exchange("/v1/conversaciones", HttpMethod.POST,
                new HttpEntity<>("{\"anuncioId\":\"" + anuncioId + "\"}", jsonHeaders(token)), String.class);
    }

    private ResponseEntity<String> enviar(String token, String conversacionId, String texto) {
        return rest.exchange("/v1/conversaciones/" + conversacionId + "/mensajes", HttpMethod.POST,
                new HttpEntity<>("{\"texto\":" + json.writeValueAsString(texto) + "}", jsonHeaders(token)), String.class);
    }

    private JsonNode misConversaciones(String token) {
        return json.readTree(rest.exchange("/v1/mis-conversaciones", HttpMethod.GET,
                new HttpEntity<>(null, conSesion(token)), String.class).getBody());
    }

    @Nested
    @DisplayName("Ciclo comprador ↔ vendedor (R9/R10/R16)")
    class CicloCompleto {

        @Test
        @DisplayName("Abrir 201 → reutilizar 200 → enviar → no leídos → marcar → responder; historial legible tras la baja del anuncio")
        void flujoCompleto() {
            Sesion vendedor = usuarioConSesion("vendedor-msg", "miembro");
            Sesion comprador = usuarioConSesion("comprador-msg", "miembro");
            Sesion moderador = usuarioConSesion("moderador-msg", "moderador");
            String anuncioId = anuncioAprobado(vendedor, moderador, "Teclado " + UUID.randomUUID());

            ResponseEntity<String> primera = abrir(comprador.token(), anuncioId);
            assertThat(primera.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String conversacionId = json.readTree(primera.getBody()).get("id").asText();

            ResponseEntity<String> repetida = abrir(comprador.token(), anuncioId);
            assertThat(repetida.getStatusCode()).as("reuso del par → 200 (R9)").isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(repetida.getBody()).get("id").asText()).isEqualTo(conversacionId);

            assertThat(enviar(comprador.token(), conversacionId, "  ¿Sigue disponible?  ").getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);

            JsonNode panelVendedor = misConversaciones(vendedor.token()).get("datos").get(0);
            assertThat(panelVendedor.get("noLeidos").asLong()).isEqualTo(1);
            assertThat(panelVendedor.get("ultimoMensaje").get("texto").asText()).isEqualTo("¿Sigue disponible?");

            ResponseEntity<String> historialAntes = rest.exchange(
                    "/v1/conversaciones/" + conversacionId + "/mensajes", HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
            assertThat(historialAntes.getBody()).contains("¿Sigue disponible?");
            JsonNode mensaje = json.readTree(historialAntes.getBody()).get("datos").get(0);
            assertThat(mensaje.get("leido").asBoolean()).as("el GET no muta leido").isFalse();

            assertThat(rest.exchange("/v1/conversaciones/" + conversacionId + "/lecturas", HttpMethod.POST,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(misConversaciones(vendedor.token()).get("datos").get(0).get("noLeidos").asLong())
                    .as("marcado explícito").isZero();

            assertThat(enviar(vendedor.token(), conversacionId, "Sí, disponible").getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(misConversaciones(comprador.token()).get("datos").get(0).get("noLeidos").asLong()).isEqualTo(1);

            rest.exchange("/v1/anuncios/" + anuncioId, HttpMethod.DELETE,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
            ResponseEntity<String> detalle = rest.exchange("/v1/conversaciones/" + conversacionId, HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(comprador.token())), String.class);
            assertThat(detalle.getStatusCode()).as("historial legible tras la baja (R16)").isEqualTo(HttpStatus.OK);
            assertThat(detalle.getBody()).contains("\"estado\":\"eliminado\"");
        }

        @Test
        @DisplayName("Cursor hacia atrás: página inicial = más recientes; la siguiente avanza")
        void cursorHaciaAtras() {
            Sesion vendedor = usuarioConSesion("vendedor-cursor-msg", "miembro");
            Sesion comprador = usuarioConSesion("comprador-cursor-msg", "miembro");
            Sesion moderador = usuarioConSesion("moderador-cursor-msg", "moderador");
            String anuncioId = anuncioAprobado(vendedor, moderador, "Monitor " + UUID.randomUUID());
            String conversacionId = json.readTree(abrir(comprador.token(), anuncioId).getBody()).get("id").asText();
            enviar(comprador.token(), conversacionId, "primero");
            enviar(comprador.token(), conversacionId, "segundo");
            enviar(comprador.token(), conversacionId, "tercero");

            JsonNode primera = json.readTree(rest.exchange(
                    "/v1/conversaciones/" + conversacionId + "/mensajes?limite=2", HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(comprador.token())), String.class).getBody());
            assertThat(primera.get("datos")).hasSize(2);
            assertThat(primera.get("datos").get(0).get("texto").asText()).isEqualTo("tercero");
            String cursor = primera.get("paginacion").get("cursorSiguiente").asText();

            JsonNode segunda = json.readTree(rest.exchange(
                    "/v1/conversaciones/" + conversacionId + "/mensajes?limite=2&cursor=" + cursor, HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(comprador.token())), String.class).getBody());
            assertThat(segunda.get("datos")).hasSize(1);
            assertThat(segunda.get("datos").get(0).get("texto").asText()).isEqualTo("primero");
            assertThat(segunda.get("paginacion").get("cursorSiguiente").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Guards (R9/R10/R16)")
    class Guards {

        @Test
        @DisplayName("Apertura: no visible → 404, propia → 422, inexistente → 404, vendedor inactivo → 404")
        void apertura() {
            Sesion vendedor = usuarioConSesion("vendedor-guards-msg", "miembro");
            Sesion comprador = usuarioConSesion("comprador-guards-msg", "miembro");
            Sesion moderador = usuarioConSesion("moderador-guards-msg", "moderador");

            // Pendiente (sin aprobar) → 404 indistinguible
            MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
            partes.add("nombre", "Pendiente " + UUID.randomUUID());
            partes.add("precio", "10.00");
            partes.add("imagenes", new ByteArrayResource(JPEG) {
                @Override
                public String getFilename() {
                    return "foto.jpg";
                }
            });
            HttpHeaders multipart = conSesion(vendedor.token());
            multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
            String pendienteId = json.readTree(rest.postForEntity("/v1/anuncios",
                    new HttpEntity<>(partes, multipart), String.class).getBody()).get("id").asText();
            assertThat(abrir(comprador.token(), pendienteId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(abrir(comprador.token(), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(rest.exchange("/v1/conversaciones", HttpMethod.POST,
                    new HttpEntity<>("{\"anuncioId\":\"" + UUID.randomUUID() + "\"}", jsonHeaders(null)), String.class)
                    .getStatusCode()).as("sin sesión").isEqualTo(HttpStatus.UNAUTHORIZED);

            String aprobadoId = anuncioAprobado(vendedor, moderador, "Propio " + UUID.randomUUID());
            assertThat(abrir(vendedor.token(), aprobadoId).getStatusCode()).as("auto-conversación (R9)")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            jdbc.update("UPDATE usuarios SET habilitado = false WHERE id = ?", vendedor.id());
            assertThat(abrir(comprador.token(), aprobadoId).getStatusCode())
                    .as("vendedor inactivo → 404 (R16)").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Participación: no participante ∈ {403}; texto vacío 422; contraparte inactiva 422")
        void participacionYEnvio() {
            Sesion vendedor = usuarioConSesion("vendedor-guard2-msg", "miembro");
            Sesion comprador = usuarioConSesion("comprador-guard2-msg", "miembro");
            Sesion intruso = usuarioConSesion("intruso-guard2-msg", "miembro");
            Sesion moderador = usuarioConSesion("moderador-guard2-msg", "moderador");
            String anuncioId = anuncioAprobado(vendedor, moderador, "Silla " + UUID.randomUUID());
            String conversacionId = json.readTree(abrir(comprador.token(), anuncioId).getBody()).get("id").asText();

            assertThat(rest.exchange("/v1/conversaciones/" + conversacionId, HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(intruso.token())), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(enviar(intruso.token(), conversacionId, "metido").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(enviar(comprador.token(), conversacionId, "   ").getStatusCode())
                    .as("texto en blanco (R10)").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            jdbc.update("UPDATE usuarios SET habilitado = false WHERE id = ?", vendedor.id());
            assertThat(enviar(comprador.token(), conversacionId, "¿sigues ahí?").getStatusCode())
                    .as("contraparte inactiva (R16)").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
}
