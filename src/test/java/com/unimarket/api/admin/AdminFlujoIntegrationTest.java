package com.unimarket.api.admin;

import com.unimarket.api.identity.SoportePostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Panel de administración end-to-end (R14/R15) contra PostgreSQL 18 real: el cambio de
 * rol y la inhabilitación surten efecto INMEDIATO sobre tokens ya emitidos (D3), las bajas
 * son invisibles para el panel, y el CRUD de categorías respeta orden/duplicados/límites
 * conservando los anuncios al borrar (SET NULL).
 */
@DisplayName("Admin — flujo HTTP completo")
class AdminFlujoIntegrationTest extends SoportePostgresIT {

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

    private static HttpHeaders jsonCon(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private static HttpHeaders conTodo(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> patch(String token, String ruta, String cuerpo) {
        return rest.exchange(ruta, HttpMethod.PATCH, new HttpEntity<>(cuerpo, jsonCon(token)), String.class);
    }

    @Nested
    @DisplayName("Usuarios (R14/R15)")
    class Usuarios {

        @Test
        @DisplayName("Efecto inmediato del rol: el token viejo del ascendido entra a moderación sin relogin")
        void rolConEfectoInmediato() {
            Sesion admin = usuarioConSesion("admin-rol", "admin");
            Sesion ascendido = usuarioConSesion("ascendido", "miembro");

            assertThat(rest.exchange("/v1/moderacion/anuncios", HttpMethod.GET,
                    new HttpEntity<>(null, conTodo(ascendido.token())), String.class).getStatusCode())
                    .as("antes: miembro no entra").isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<String> cambiado = patch(admin.token(), "/v1/admin/usuarios/" + ascendido.id() + "/rol",
                    "{\"rol\":\"moderador\"}");
            assertThat(cambiado.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(cambiado.getBody()).get("rol").asText()).isEqualTo("moderador");

            assertThat(rest.exchange("/v1/moderacion/anuncios", HttpMethod.GET,
                    new HttpEntity<>(null, conTodo(ascendido.token())), String.class).getStatusCode())
                    .as("mismo token, autoridad recargada de la BD (D3)").isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("R14: auto-rol 403; fuera de dominio o igual 422; miembro en /admin 403")
        void guardsDeRol() {
            Sesion admin = usuarioConSesion("admin-guards", "admin");
            Sesion miembro = usuarioConSesion("miembro-guards", "miembro");

            assertThat(patch(admin.token(), "/v1/admin/usuarios/" + admin.id() + "/rol", "{\"rol\":\"miembro\"}")
                    .getStatusCode()).as("su propio rol").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(patch(admin.token(), "/v1/admin/usuarios/" + miembro.id() + "/rol", "{\"rol\":\"superadmin\"}")
                    .getStatusCode()).as("fuera de dominio").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(patch(admin.token(), "/v1/admin/usuarios/" + miembro.id() + "/rol", "{\"rol\":\"miembro\"}")
                    .getStatusCode()).as("igual al actual").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            assertThat(rest.exchange("/v1/admin/usuarios", HttpMethod.GET,
                    new HttpEntity<>(null, conTodo(miembro.token())), String.class).getStatusCode())
                    .as("miembro no es admin").isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Inhabilitar corta la sesión viva (401) y el login; re-habilitar la restaura")
        void habilitacionCortaSesion() {
            Sesion admin = usuarioConSesion("admin-hab", "admin");
            Sesion victima = usuarioConSesion("victima-hab", "miembro");
            String emailVictima = jdbc.queryForObject(
                    "SELECT email FROM usuarios WHERE id = ?", String.class, victima.id());

            assertThat(patch(admin.token(), "/v1/admin/usuarios/" + victima.id() + "/habilitacion", "{\"habilitado\":false}")
                    .getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(rest.exchange("/v1/usuarios/me", HttpMethod.GET,
                    new HttpEntity<>(null, conTodo(victima.token())), String.class).getStatusCode())
                    .as("token vigente cortado (R15/D3)").isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(rest.postForEntity("/v1/auth/login",
                    Map.of("email", emailVictima, "contrasena", "secreta123"), String.class).getStatusCode())
                    .as("login indistinguible").isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(patch(admin.token(), "/v1/admin/usuarios/" + victima.id() + "/habilitacion", "{\"habilitado\":true}")
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(rest.postForEntity("/v1/auth/login",
                    Map.of("email", emailVictima, "contrasena", "secreta123"), String.class).getStatusCode())
                    .as("re-habilitar restaura el login").isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Listado: filtro por rol y las cuentas dadas de baja no existen para el panel")
        void listado() {
            Sesion admin = usuarioConSesion("admin-lista", "admin");
            Sesion moderador = usuarioConSesion("moderador-lista", "moderador");
            Sesion dadoDeBaja = usuarioConSesion("baja-lista", "miembro");
            jdbc.update("UPDATE usuarios SET fecha_eliminacion = now() WHERE id = ?", dadoDeBaja.id());

            JsonNode filtrado = json.readTree(rest.exchange("/v1/admin/usuarios?rol=moderador&limite=100", HttpMethod.GET,
                    new HttpEntity<>(null, conTodo(admin.token())), String.class).getBody());
            assertThat(filtrado.get("datos")).allMatch(nodo -> "moderador".equals(nodo.get("rol").asText()));
            assertThat(filtrado.get("datos").toString()).contains(moderador.id().toString());

            JsonNode completo = json.readTree(rest.exchange("/v1/admin/usuarios?limite=100", HttpMethod.GET,
                    new HttpEntity<>(null, conTodo(admin.token())), String.class).getBody());
            assertThat(completo.get("datos").toString())
                    .as("una baja no aparece (R15)").doesNotContain(dadoDeBaja.id().toString());

            assertThat(patch(admin.token(), "/v1/admin/usuarios/" + dadoDeBaja.id() + "/habilitacion", "{\"habilitado\":false}")
                    .getStatusCode()).as("operar sobre una baja → 404").isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Categorías (moderador)")
    class Categorias {

        @Test
        @DisplayName("Crear continúa el orden (MAX+1), duplica 409, límites 422 y miembro no puede")
        void crearYValidar() {
            Sesion moderador = usuarioConSesion("moderador-cat", "moderador");
            Sesion miembro = usuarioConSesion("miembro-cat", "miembro");
            String nombre = "Instrumentos " + UUID.randomUUID();
            int maxAntes = jdbc.queryForObject("SELECT coalesce(max(orden),0) FROM categorias", Integer.class);

            ResponseEntity<String> creada = rest.postForEntity("/v1/categorias",
                    new HttpEntity<>("{\"nombre\":\"" + nombre + "\",\"icono\":\"🎸\"}", jsonCon(moderador.token())), String.class);
            assertThat(creada.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode cuerpo = json.readTree(creada.getBody());
            assertThat(cuerpo.get("orden").asInt()).isEqualTo(maxAntes + 1);
            String categoriaId = cuerpo.get("id").asText();

            assertThat(rest.postForEntity("/v1/categorias",
                    new HttpEntity<>("{\"nombre\":\"" + nombre + "\"}", jsonCon(moderador.token())), String.class)
                    .getStatusCode()).as("duplicado").isEqualTo(HttpStatus.CONFLICT);
            assertThat(rest.postForEntity("/v1/categorias",
                    new HttpEntity<>("{\"nombre\":\"X " + UUID.randomUUID() + "\",\"icono\":\"12345678901\"}", jsonCon(moderador.token())), String.class)
                    .getStatusCode()).as("icono > 10").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(rest.postForEntity("/v1/categorias",
                    new HttpEntity<>("{\"nombre\":\"   \"}", jsonCon(moderador.token())), String.class)
                    .getStatusCode()).as("nombre vacío").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(rest.postForEntity("/v1/categorias",
                    new HttpEntity<>("{\"nombre\":\"Otro " + UUID.randomUUID() + "\"}", jsonCon(miembro.token())), String.class)
                    .getStatusCode()).as("miembro no escribe catálogo").isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<String> editada = patch(moderador.token(), "/v1/categorias/" + categoriaId,
                    "{\"nombre\":\"" + nombre + " editada\"}");
            assertThat(editada.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(editada.getBody()).get("nombre").asText()).contains("editada");
            assertThat(patch(moderador.token(), "/v1/categorias/999999", "{\"nombre\":\"No existe\"}")
                    .getStatusCode()).as("inexistente").isEqualTo(HttpStatus.NOT_FOUND);

            // Borrado físico con anuncio sobreviviente (SET NULL, DBDD §5).
            UUID vendedorV = jdbc.queryForObject(
                    "INSERT INTO usuarios (email, nombre, hash_contrasena) VALUES (?, 'V', 'h') RETURNING id",
                    UUID.class, "cat-setnull-" + UUID.randomUUID() + "@uni.edu");
            UUID anuncioId = jdbc.queryForObject(
                    "INSERT INTO anuncios (nombre, precio, vendedor_id, categoria_id) "
                            + "VALUES ('Con categoria', 5.00, ?, ?) RETURNING id",
                    UUID.class, vendedorV, Integer.parseInt(categoriaId));
            assertThat(rest.exchange("/v1/categorias/" + categoriaId, HttpMethod.DELETE,
                    new HttpEntity<>(null, conTodo(moderador.token())), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(jdbc.queryForObject("SELECT categoria_id FROM anuncios WHERE id = ?", Integer.class, anuncioId))
                    .as("el anuncio sobrevive sin categoría").isNull();
        }
    }
}
