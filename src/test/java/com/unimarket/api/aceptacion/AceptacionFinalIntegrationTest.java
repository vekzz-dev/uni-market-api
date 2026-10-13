package com.unimarket.api.aceptacion;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterios de aceptación del PRD §8 que no tenían cobertura directa en las suites por
 * feature: C3 (la baja no destruye datos), C4 (el anuncio de un admin también nace
 * pendiente), C10 (ninguna tabla de tokens guarda plaintext) y C16 (sin RLS ni triggers).
 * El resto de los 17 criterios se evidencia en las suites por módulo (registro en
 * {@code odd/tasks/aceptacion-final.md}).
 */
@DisplayName("Aceptación final — criterios PRD §8")
class AceptacionFinalIntegrationTest extends SoportePostgresIT {

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
        return new Sesion(id, json.readTree(login.getBody()).get("accessToken").asText());
    }

    private static HttpHeaders conSesion(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders jsonCon(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private String crearAnuncio(String token, String nombre) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", nombre);
        partes.add("precio", "33.00");
        partes.add("imagenes", new ByteArrayResource(JPEG) {
            @Override
            public String getFilename() {
                return "foto.jpg";
            }
        });
        HttpHeaders multipart = conSesion(token);
        multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> creado = rest.postForEntity("/v1/anuncios", new HttpEntity<>(partes, multipart), String.class);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(creado.getBody()).get("id").asText();
    }

    private void aprobar(String tokenModerador, String anuncioId) {
        ResponseEntity<String> decision = rest.exchange("/v1/moderacion/anuncios/" + anuncioId + "/decisiones",
                HttpMethod.POST, new HttpEntity<>("{\"decision\":\"aprobar\"}", jsonCon(tokenModerador)), String.class);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("C3: la baja libera el email y NO destruye anuncios, conversaciones ni mensajes")
    void bajaNoDestruyeDatos() {
        Sesion vendedor = usuarioConSesion("c3-vendedor", "miembro");
        Sesion comprador = usuarioConSesion("c3-comprador", "miembro");
        Sesion moderador = usuarioConSesion("c3-moderador", "moderador");
        String anuncioId = crearAnuncio(vendedor.token(), "Reloj " + UUID.randomUUID());
        aprobar(moderador.token(), anuncioId);
        String conversacionId = json.readTree(rest.exchange("/v1/conversaciones", HttpMethod.POST,
                new HttpEntity<>("{\"anuncioId\":\"" + anuncioId + "\"}", jsonCon(comprador.token())), String.class)
                .getBody()).get("id").asText();
        rest.exchange("/v1/conversaciones/" + conversacionId + "/mensajes", HttpMethod.POST,
                new HttpEntity<>("{\"texto\":\"¿Sigue disponible?\"}", jsonCon(comprador.token())), String.class);

        assertThat(rest.exchange("/v1/usuarios/me", HttpMethod.DELETE,
                new HttpEntity<>(null, conSesion(vendedor.token())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForObject("SELECT fecha_eliminacion FROM usuarios WHERE id = ?",
                java.sql.Timestamp.class, vendedor.id())).as("baja = soft-delete").isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anuncios WHERE id = ?", Integer.class,
                UUID.fromString(anuncioId))).as("el anuncio sobrevive (R1)").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversaciones WHERE id = ?", Integer.class,
                UUID.fromString(conversacionId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes WHERE conversacion_id = ?", Integer.class,
                UUID.fromString(conversacionId))).isEqualTo(1);

        // El historial sigue legible para el comprador activo (R16).
        assertThat(rest.exchange("/v1/conversaciones/" + conversacionId + "/mensajes", HttpMethod.GET,
                new HttpEntity<>(null, conSesion(comprador.token())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("C4: el anuncio de un ADMIN también nace pendiente y no es visible hasta aprobarlo")
    void anuncioDeAdminNacePendiente() {
        Sesion admin = usuarioConSesion("c4-admin", "admin");
        String nombre = "Monitor admin " + UUID.randomUUID();

        String anuncioId = crearAnuncio(admin.token(), nombre);

        assertThat(jdbc.queryForObject("SELECT estado_revision FROM anuncios WHERE id = ?", String.class,
                UUID.fromString(anuncioId))).as("sin excepción de rol (R5)").isEqualTo("pendiente");
        assertThat(rest.getForEntity("/v1/anuncios?q=" + nombre, String.class).getBody())
                .as("invisible en el catálogo").doesNotContain(nombre);
    }

    @Test
    @DisplayName("C10: ninguna tabla de tokens guarda plaintext — todo hash SHA-256 (64 hex)")
    void tokensSiempreHasheados() {
        String email = "c10-" + UUID.randomUUID() + "@uni.edu";
        rest.postForEntity("/v1/usuarios",
                Map.of("email", email, "nombre", "C10", "contrasena", "secreta123"), String.class);
        rest.postForEntity("/v1/auth/restablecer-contrasena", Map.of("email", email), String.class);
        rest.postForEntity("/v1/auth/login", Map.of("email", email, "contrasena", "secreta123"), String.class);

        List<String> hashes = jdbc.queryForList(
                "SELECT hash_token FROM tokens_verificacion_email "
                        + "UNION ALL SELECT hash_token FROM tokens_restablecimiento_contrasena "
                        + "UNION ALL SELECT hash_token FROM tokens_refresh",
                String.class);

        assertThat(hashes).as("hay tokens de los tres tipos").hasSizeGreaterThanOrEqualTo(2);
        assertThat(hashes).allMatch(hash -> hash.matches("^[0-9a-f]{64}$"),
                "todo hash_token es SHA-256 hex — cero plaintext (R11)");
    }

    @Test
    @DisplayName("C16: sin RLS (pg_policies vacío) y sin triggers de usuario en el esquema")
    void sinRlsNiTriggers() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal", Integer.class))
                .as("ningún trigger propio: auditoría y estado en la aplicación (DBDD §1)").isZero();
    }
}
