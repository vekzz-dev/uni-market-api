package com.unimarket.api.moderation;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Denuncias de anuncios end-to-end (R19, issue #9) contra PostgreSQL real: un miembro
 * denuncia, el moderador ve el caso con el contador, resuelve, y retraer el anuncio cierra
 * las denuncias abiertas. La denuncia informa: nunca retira el anuncio por sí sola.
 */
@DisplayName("Denuncias — flujo HTTP completo (R19)")
class DenunciasFlujoIntegrationTest extends SoportePostgresIT {

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
        String email = "denuncia-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email, rol) "
                        + "VALUES (?, ?, ?, now(), ?) RETURNING id",
                UUID.class, email, "Usuario " + rol, encoder.encode("secreta123"), rol);
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

    private String anuncioAprobado(Sesion vendedor, Sesion moderador) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", "Denunciable " + UUID.randomUUID());
        partes.add("precio", "10.00");
        partes.add("imagenes", new ByteArrayResource(JPEG) {
            @Override
            public String getFilename() {
                return "foto.jpg";
            }
        });
        HttpHeaders cabeceras = conSesion(vendedor.token());
        cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> creado = rest.postForEntity(
                "/v1/anuncios", new HttpEntity<>(partes, cabeceras), String.class);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = json.readTree(creado.getBody()).get("id").asText();
        assertThat(decidir(moderador, id, "aprobar", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private ResponseEntity<String> decidir(Sesion moderador, String anuncioId, String decision, String motivo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(moderador.token());
        String cuerpo = motivo == null
                ? "{\"decision\":\"" + decision + "\"}"
                : "{\"decision\":\"" + decision + "\",\"motivoRechazo\":\"" + motivo + "\"}";
        return rest.exchange("/v1/moderacion/anuncios/" + anuncioId + "/decisiones", HttpMethod.POST,
                new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private ResponseEntity<String> denunciar(Sesion denunciante, String anuncioId, String motivo, String detalle) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(denunciante.token());
        String cuerpo = detalle == null
                ? "{\"motivo\":\"" + motivo + "\"}"
                : "{\"motivo\":\"" + motivo + "\",\"detalle\":\"" + detalle + "\"}";
        return rest.exchange("/v1/anuncios/" + anuncioId + "/denuncias", HttpMethod.POST,
                new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private JsonNode colaDeDenuncias(Sesion moderador) {
        ResponseEntity<String> respuesta = rest.exchange("/v1/moderacion/denuncias?estado=abierta&limite=100",
                HttpMethod.GET, new HttpEntity<>(null, conSesion(moderador.token())), String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(respuesta.getBody());
    }

    @Test
    @DisplayName("Denunciar: 201, aparece en la cola y nunca expone al denunciante")
    void denunciar_apareceEnLaColaSinDenunciante() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        Sesion comprador = sesion("miembro");
        String anuncioId = anuncioAprobado(vendedor, moderador);

        ResponseEntity<String> creada = denunciar(comprador, anuncioId, "estafa", null);

        assertThat(creada.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode cuerpo = json.readTree(creada.getBody());
        assertThat(cuerpo.get("estado").asText()).isEqualTo("abierta");
        assertThat(creada.getBody()).doesNotContain("denuncianteId", "denunciante_id",
                comprador.id().toString());

        JsonNode cola = colaDeDenuncias(moderador);
        assertThat(cola.get("datos").toString()).contains(anuncioId);
        assertThat(cola.get("datos").toString()).doesNotContain(comprador.id().toString());
        assertThat(cola.get("paginacion").get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Duplicado 409, anuncio propio 422, no visible 404 y anónimo 401")
    void reglasDeLaDenuncia() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        Sesion comprador = sesion("miembro");
        String anuncioId = anuncioAprobado(vendedor, moderador);

        assertThat(denunciar(comprador, anuncioId, "spam", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(denunciar(comprador, anuncioId, "spam", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(denunciar(vendedor, anuncioId, "spam", null).getStatusCode())
                .as("nadie denuncia su propio anuncio").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        HttpHeaders anonimo = new HttpHeaders();
        anonimo.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> sinSesion = rest.exchange("/v1/anuncios/" + anuncioId + "/denuncias",
                HttpMethod.POST, new HttpEntity<>("{\"motivo\":\"spam\"}", anonimo), String.class);
        assertThat(sinSesion.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String pendiente = crearPendiente(vendedor);
        assertThat(denunciar(comprador, pendiente, "spam", null).getStatusCode())
                .as("un anuncio no visible se comporta como inexistente (R4)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String crearPendiente(Sesion vendedor) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", "Pendiente " + UUID.randomUUID());
        partes.add("precio", "10.00");
        partes.add("imagenes", new ByteArrayResource(JPEG) {
            @Override
            public String getFilename() {
                return "foto.jpg";
            }
        });
        HttpHeaders cabeceras = conSesion(vendedor.token());
        cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> creado = rest.postForEntity(
                "/v1/anuncios", new HttpEntity<>(partes, cabeceras), String.class);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(creado.getBody()).get("id").asText();
    }

    @Test
    @DisplayName("El motivo 'otro' exige detalle; el resto no")
    void detalleObligatorioSoloEnOtro() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        Sesion comprador = sesion("miembro");
        String anuncioId = anuncioAprobado(vendedor, moderador);

        assertThat(denunciar(comprador, anuncioId, "otro", null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(denunciar(comprador, anuncioId, "otro", "  ").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(denunciar(comprador, anuncioId, "otro", "vende bolitas").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("La cola de anuncios cuenta las denuncias abiertas por anuncio")
    void colaDeAnunciosConContador() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        String anuncioId = anuncioAprobado(vendedor, moderador);
        denunciar(sesion("miembro"), anuncioId, "spam", null);
        denunciar(sesion("miembro"), anuncioId, "estafa", null);

        ResponseEntity<String> cola = rest.exchange("/v1/moderacion/anuncios?estadoRevision=aprobado&limite=100",
                HttpMethod.GET, new HttpEntity<>(null, conSesion(moderador.token())), String.class);

        assertThat(cola.getStatusCode()).isEqualTo(HttpStatus.OK);
        // La cola es FIFO y el contenedor es compartido: se busca la fila, no se asume la posición.
        JsonNode fila = null;
        for (JsonNode candidata : json.readTree(cola.getBody()).get("datos")) {
            if (candidata.get("id").asText().equals(anuncioId)) {
                fila = candidata;
            }
        }
        assertThat(fila).as("el anuncio denunciado está en la cola").isNotNull();
        assertThat(fila.get("denunciasAbiertas").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("Resolver: una denuncia cerrada no se vuelve a resolver")
    void resolverUnaSolaVez() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        String anuncioId = anuncioAprobado(vendedor, moderador);
        String denunciaId = json.readTree(denunciar(sesion("miembro"), anuncioId, "spam", null).getBody())
                .get("id").asText();

        ResponseEntity<String> revisada = resolver(moderador, denunciaId, "revisada");
        ResponseEntity<String> otraVez = resolver(moderador, denunciaId, "descartada");

        assertThat(revisada.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(revisada.getBody()).get("estado").asText()).isEqualTo("revisada");
        assertThat(json.readTree(revisada.getBody()).get("resueltoPor").asText()).isEqualTo(moderador.id().toString());
        assertThat(otraVez.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    private ResponseEntity<String> resolver(Sesion moderador, String denunciaId, String estado) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(moderador.token());
        return rest.exchange("/v1/moderacion/denuncias/" + denunciaId + "/resolucion", HttpMethod.POST,
                new HttpEntity<>("{\"estado\":\"" + estado + "\"}", cabeceras), String.class);
    }

    @Test
    @DisplayName("Retraer el anuncio cierra sus denuncias abiertas como revisadas")
    void retraerCierraLasDenuncias() {
        Sesion vendedor = sesion("miembro");
        Sesion moderador = sesion("moderador");
        String anuncioId = anuncioAprobado(vendedor, moderador);
        String denunciaId = json.readTree(denunciar(sesion("miembro"), anuncioId, "prohibido", null).getBody())
                .get("id").asText();

        assertThat(decidir(moderador, anuncioId, "retraer", "producto prohibido").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String estado = jdbc.queryForObject(
                "SELECT estado FROM denuncias WHERE id = ?", String.class, UUID.fromString(denunciaId));
        assertThat(estado).isEqualTo("revisada");
        assertThat(colaDeDenuncias(moderador).get("datos").toString()).doesNotContain(denunciaId);
    }
}
