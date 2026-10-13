package com.unimarket.api.moderation;

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
 * Moderación end-to-end contra PostgreSQL 18 real (PRD §6): el ciclo publicar → decidir
 * → visible funciona ENTERO por API (sin manipular la BD), con los guards de R5:
 * anti-auto-decisión, motivo obligatorio, estados inválidos, roles y no-enumeración.
 */
@DisplayName("Moderación — flujo HTTP completo")
class ModeracionFlujoIntegrationTest extends SoportePostgresIT {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PasswordEncoder encoder;

    private record Sesion(UUID id, String token, String rol) {
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
        return new Sesion(id, json.readTree(login.getBody()).get("accessToken").asText(), rol);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static HttpHeaders conSesion(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String crearAnuncio(String token, String nombre) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", nombre);
        partes.add("precio", "42.00");
        partes.add("imagenes", new ByteArrayResource(JPEG) {
            @Override
            public String getFilename() {
                return "foto.jpg";
            }
        });
        HttpHeaders cabeceras = conSesion(token);
        cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> creado = rest.postForEntity("/v1/anuncios", new HttpEntity<>(partes, cabeceras), String.class);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(creado.getBody()).get("id").asText();
    }

    private ResponseEntity<String> decidir(String token, String anuncioId, String decision, String motivo) {
        String cuerpo = motivo == null
                ? "{\"decision\":\"" + decision + "\"}"
                : "{\"decision\":\"" + decision + "\",\"motivoRechazo\":\"" + motivo + "\"}";
        return rest.exchange("/v1/moderacion/anuncios/" + anuncioId + "/decisiones", HttpMethod.POST,
                new HttpEntity<>(cuerpo, jsonHeadersConSesion(token)), String.class);
    }

    private static HttpHeaders jsonHeadersConSesion(String token) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private boolean colaContiene(String token, String anuncioId, String estado) {
        ResponseEntity<String> cola = rest.exchange(
                "/v1/moderacion/anuncios?estadoRevision=" + estado + "&limite=100", HttpMethod.GET,
                new HttpEntity<>(null, conSesion(token)), String.class);
        assertThat(cola.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode item : json.readTree(cola.getBody()).get("datos")) {
            if (item.get("id").asText().equals(anuncioId)) {
                return true;
            }
        }
        return false;
    }

    private boolean catalogoContiene(String nombre) {
        ResponseEntity<String> catalogo = rest.getForEntity("/v1/anuncios?q=" + nombre, String.class);
        return catalogo.getBody().contains(nombre);
    }

    @Nested
    @DisplayName("Ciclo publicar → decidir → visible (R4/R5)")
    class CicloCompleto {

        @Test
        @DisplayName("Aprobar por API hace visible el anuncio; retraer lo rechaza con motivo para el vendedor")
        void flujoCompleto() {
            Sesion vendedor = usuarioConSesion("vendedor-mod", "miembro");
            Sesion moderador = usuarioConSesion("moderador", "moderador");
            String nombre = "Longboard " + UUID.randomUUID();

            String id = crearAnuncio(vendedor.token(), nombre);
            assertThat(catalogoContiene(nombre)).as("pendiente: invisible").isFalse();
            assertThat(colaContiene(moderador.token(), id, "pendiente")).isTrue();

            ResponseEntity<String> aprobado = decidir(moderador.token(), id, "aprobar", null);
            assertThat(aprobado.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(aprobado.getBody()).get("estadoRevision").asText()).isEqualTo("aprobado");
            assertThat(catalogoContiene(nombre)).as("aprobado: visible por API real").isTrue();

            ResponseEntity<String> retraido = decidir(moderador.token(), id, "retraer", "imagen inapropiada");
            assertThat(json.readTree(retraido.getBody()).get("estadoRevision").asText()).isEqualTo("rechazado");

            ResponseEntity<String> misAnuncios = rest.exchange("/v1/mis-anuncios", HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
            assertThat(misAnuncios.getBody()).contains("imagen inapropiada").contains("rechazado");
            assertThat(catalogoContiene(nombre)).as("rechazado: invisible de nuevo").isFalse();
        }
    }

    @Nested
    @DisplayName("Guards de R5")
    class Guards {

        @Test
        @DisplayName("Estados inválidos y motivo obligatorio → 422; enum inválido → 400")
        void validaciones() {
            Sesion vendedor = usuarioConSesion("vendedor-valid", "miembro");
            Sesion moderador = usuarioConSesion("moderador-valid", "moderador");
            String id = crearAnuncio(vendedor.token(), "Patines " + UUID.randomUUID());

            assertThat(decidir(moderador.token(), id, "rechazar", null).getStatusCode())
                    .as("rechazar sin motivo").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(decidir(moderador.token(), id, "retraer", "x").getStatusCode())
                    .as("retraer un pendiente").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(decidir(moderador.token(), id, "aprobar", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(decidir(moderador.token(), id, "aprobar", null).getStatusCode())
                    .as("aprobar un ya aprobado").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(decidir(moderador.token(), id, "explotar", null).getStatusCode())
                    .as("enum inválido").isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Rol sin permiso → 403; inexistente → 404; sin sesión → 401")
        void rolesYRecursos() {
            Sesion vendedor = usuarioConSesion("vendedor-roles", "miembro");
            Sesion miembro = usuarioConSesion("miembro-roles", "miembro");
            String id = crearAnuncio(vendedor.token(), "Raqueta " + UUID.randomUUID());

            assertThat(decidir(miembro.token(), id, "aprobar", null).getStatusCode())
                    .as("miembro no decide").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(decidir(usuarioConSesion("moderador-roles", "moderador").token(),
                    UUID.randomUUID().toString(), "aprobar", null).getStatusCode())
                    .as("inexistente").isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(rest.exchange("/v1/moderacion/anuncios", HttpMethod.GET,
                    new HttpEntity<>(null, new HttpHeaders()), String.class).getStatusCode())
                    .as("sin sesión").isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Anti-auto-decisión: un moderador no decide su PROPIO anuncio (403); un admin sí decide ajenos")
        void antiAutoYAdmin() {
            Sesion moderadorVendedor = usuarioConSesion("moderador-dual", "moderador");
            Sesion admin = usuarioConSesion("admin-mod", "admin");
            String propio = crearAnuncio(moderadorVendedor.token(), "Mi propio anuncio " + UUID.randomUUID());

            assertThat(decidir(moderadorVendedor.token(), propio, "aprobar", null).getStatusCode())
                    .as("nadie decide lo suyo, ni un moderador").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(decidir(admin.token(), propio, "aprobar", null).getStatusCode())
                    .as("admin decide ajenos por jerarquía").isEqualTo(HttpStatus.OK);
        }
    }
}
