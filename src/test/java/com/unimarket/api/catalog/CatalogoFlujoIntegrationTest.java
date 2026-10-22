package com.unimarket.api.catalog;

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flujo end-to-end del catálogo contra PostgreSQL 18 real (PRD §6): crear multipart,
 * invisibilidad hasta aprobar (R4), ownership (R7), ciclo de vida (R5/R6), baja lógica
 * que conserva objetos (R1/R8), rechazos de subida sin objetos huérfanos y paginación
 * por cursor (D4). La aprobación se simula por repositorio: la API de moderación llega
 * en la siguiente feature.
 */
@DisplayName("Catálogo — flujo HTTP completo")
class CatalogoFlujoIntegrationTest extends SoportePostgresIT {

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

    /** Usuario confirmado y con sesión: alta por SQL (constructor protegido fuera del paquete). */
    private SesionDePrueba usuarioConSesion(String etiqueta) {
        return usuarioConSesion(etiqueta, "Usuario " + etiqueta);
    }

    /** Variante con nombre controlado, para las reglas que dependen de él (R17). */
    private SesionDePrueba usuarioConSesion(String etiqueta, String nombre) {
        String email = etiqueta.toLowerCase() + "-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email) "
                        + "VALUES (?, ?, ?, now()) RETURNING id",
                UUID.class, email, nombre, encoder.encode("secreta123"));
        ResponseEntity<String> login = rest.postForEntity(
                "/v1/auth/login", new HttpEntity<>(java.util.Map.of("email", email, "contrasena", "secreta123"), jsonHeaders()), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new SesionDePrueba(id, json.readTree(login.getBody()).get("accessToken").asText());
    }

    private record SesionDePrueba(UUID id, String token) {
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return cabeceras;
    }

    /** JSON + sesión: cuerpo con content-type JSON y bearer. */
    private static HttpHeaders jsonConSesion(String token) {
        HttpHeaders cabeceras = jsonHeaders();
        cabeceras.setBearerAuth(token);
        return cabeceras;
    }

    private static HttpHeaders conSesion(String token) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(token);
        return cabeceras;
    }

    private ResponseEntity<String> crearAnuncio(String token, String nombre, String precio, byte[]... imagenes) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("nombre", nombre);
        partes.add("precio", precio);
        partes.add("descripcion", "Descripción de prueba");
        for (byte[] imagen : imagenes) {
            partes.add("imagenes", new ByteArrayResource(imagen) {
                @Override
                public String getFilename() {
                    return "foto.jpg";
                }
            });
        }
        HttpHeaders cabeceras = conSesion(token);
        cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/v1/anuncios", new HttpEntity<>(partes, cabeceras), String.class);
    }

    private void aprobar(UUID anuncioId, UUID revisorId) {
        Anuncio anuncio = anuncios.findById(anuncioId).orElseThrow();
        anuncio.setEstadoRevision(EstadoRevision.aprobado);
        anuncio.setRevisadoPor(revisorId);
        anuncio.setFechaRevision(java.time.Instant.now());
        anuncios.saveAndFlush(anuncio);
    }

    private static long objetosEnDisco() {
        try (var flujo = Files.walk(SoportePostgresIT.MEDIA_DIR)) {
            return flujo.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("Publicar → invisible → aprobar → visible (R4/R5)")
    class CicloDeVisible {

        @Test
        @DisplayName("Nace pendiente, el catálogo no lo expone y tras aprobar aparece")
        void flujoDeVisibilidad() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-vis");
            String nombre = "Skate " + UUID.randomUUID();

            ResponseEntity<String> creado = crearAnuncio(vendedor.token(), nombre, "150.50", JPEG);
            assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(creado.getHeaders().getLocation()).isNotNull();
            JsonNode cuerpo = json.readTree(creado.getBody());
            assertThat(cuerpo.get("estadoRevision").asText()).isEqualTo("pendiente");
            String id = cuerpo.get("id").asText();
            assertThat(cuerpo.get("imagenUrls")).hasSize(1);
            assertThat(creado.getBody())
                    .doesNotContain("imagenesPaths")
                    .doesNotContain("imagenes_paths");

            assertThat(rest.getForEntity("/v1/anuncios?q=" + nombre, String.class).getBody())
                    .doesNotContain(nombre);
            assertThat(rest.getForEntity("/v1/anuncios/" + id, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            aprobar(UUID.fromString(id), vendedor.id());

            ResponseEntity<String> catalogo = rest.getForEntity("/v1/anuncios?q=" + nombre, String.class);
            assertThat(catalogo.getBody()).contains(nombre);
            ResponseEntity<String> detalle = rest.getForEntity("/v1/anuncios/" + id, String.class);
            assertThat(detalle.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detalle.getBody()).contains("\"estado\":\"disponible\"").doesNotContain("imagenesPaths");
        }
    }

    @Nested
    @DisplayName("Ownership y edición (R6/R7)")
    class Guards {

        @Test
        @DisplayName("Editar ajeno → 403; inexistente → 404; edición resetea la revisión")
        void editarAjenoYReset() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-guard");
            SesionDePrueba intruso = usuarioConSesion("intruso-guard");
            String id = json.readTree(crearAnuncio(vendedor.token(), "Kayak " + UUID.randomUUID(), "300", JPEG).getBody())
                    .get("id").asText();
            aprobar(UUID.fromString(id), vendedor.id());

            ResponseEntity<String> ajeno = rest.exchange("/v1/anuncios/" + id + "/contenido", HttpMethod.PATCH,
                    new HttpEntity<>("{\"nombre\":\"hackeado\"}", jsonConSesion(intruso.token())), String.class);
            assertThat(ajeno.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<String> inexistente = rest.exchange("/v1/anuncios/" + UUID.randomUUID() + "/contenido", HttpMethod.PATCH,
                    new HttpEntity<>("{\"nombre\":\"x\"}", jsonConSesion(vendedor.token())), String.class);
            assertThat(inexistente.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            HttpHeaders cabeceras = jsonHeaders();
            cabeceras.setBearerAuth(vendedor.token());
            ResponseEntity<String> editado = rest.exchange("/v1/anuncios/" + id + "/contenido", HttpMethod.PATCH,
                    new HttpEntity<>("{\"nombre\":\"Kayak corregido\"}", cabeceras), String.class);
            assertThat(editado.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode cuerpo = json.readTree(editado.getBody());
            assertThat(cuerpo.get("estadoRevision").asText()).isEqualTo("pendiente");
            assertThat(cuerpo.get("motivoRechazo").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Paginación por cursor (D4)")
    class Paginacion {

        @Test
        @DisplayName("Cursor de fecha: dos páginas sin repetir y última sin cursor")
        void cursorFecha() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-pag");
            String etiqueta = "pag-" + UUID.randomUUID();
            for (int i = 0; i < 3; i++) {
                String id = json.readTree(crearAnuncio(vendedor.token(), etiqueta + " " + i, "10", JPEG).getBody()).get("id").asText();
                aprobar(UUID.fromString(id), vendedor.id());
            }

            JsonNode primera = json.readTree(rest.getForEntity(
                    "/v1/anuncios?q=" + etiqueta + "&limite=2", String.class).getBody());
            assertThat(primera.get("datos")).hasSize(2);
            String cursor = primera.get("paginacion").get("cursorSiguiente").asText();

            JsonNode segunda = json.readTree(rest.getForEntity(
                    "/v1/anuncios?q=" + etiqueta + "&limite=2&cursor=" + cursor, String.class).getBody());
            assertThat(segunda.get("datos")).hasSize(1);
            assertThat(segunda.get("paginacion").get("cursorSiguiente").isNull()).isTrue();
        }

        @Test
        @DisplayName("Orden por precio ascendente con cursor de precio")
        void cursorPrecio() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-precio");
            String etiqueta = "precio-" + UUID.randomUUID();
            for (String precio : List.of("5.00", "15.00", "25.00")) {
                String id = json.readTree(crearAnuncio(vendedor.token(), etiqueta, precio, JPEG).getBody()).get("id").asText();
                aprobar(UUID.fromString(id), vendedor.id());
            }

            JsonNode primera = json.readTree(rest.getForEntity(
                    "/v1/anuncios?q=" + etiqueta + "&orden=precio&limite=2", String.class).getBody());
            assertThat(primera.get("datos")).hasSize(2);
            assertThat(primera.get("datos").get(0).get("precio").decimalValue()).isEqualByComparingTo("5.00");

            String cursor = primera.get("paginacion").get("cursorSiguiente").asText();
            JsonNode segunda = json.readTree(rest.getForEntity(
                    "/v1/anuncios?q=" + etiqueta + "&orden=precio&limite=2&cursor=" + cursor, String.class).getBody());
            assertThat(segunda.get("datos")).hasSize(1);
            assertThat(segunda.get("datos").get(0).get("precio").decimalValue()).isEqualByComparingTo("25.00");
        }
    }

    @Nested
    @DisplayName("Ciclo de vida y storage (R1/R8)")
    class CicloDeVida {

        @Test
        @DisplayName("Visibilidad solo sobre aprobado; vendido único; la baja conserva los objetos")
        void visibilidadVendidoBaja() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-vida");
            String id = json.readTree(crearAnuncio(vendedor.token(), "Guitarra " + UUID.randomUUID(), "80", JPEG).getBody())
                    .get("id").asText();

            ResponseEntity<String> sobrePendiente = rest.exchange("/v1/anuncios/" + id + "/visibilidad", HttpMethod.POST,
                    new HttpEntity<>("{\"visible\":false}", jsonConSesion(vendedor.token())), String.class);
            assertThat(sobrePendiente.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            aprobar(UUID.fromString(id), vendedor.id());

            ResponseEntity<String> oculto = rest.exchange("/v1/anuncios/" + id + "/visibilidad", HttpMethod.POST,
                    new HttpEntity<>("{\"visible\":false}", jsonConSesion(vendedor.token())), String.class);
            assertThat(json.readTree(oculto.getBody()).get("estado").asText()).isEqualTo("inactivo");

            ResponseEntity<String> vendido = rest.exchange("/v1/anuncios/" + id + "/vendido", HttpMethod.POST,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
            assertThat(json.readTree(vendido.getBody()).get("estado").asText()).isEqualTo("vendido");

            ResponseEntity<String> repetido = rest.exchange("/v1/anuncios/" + id + "/vendido", HttpMethod.POST,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
            assertThat(repetido.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            long objetosAntes = objetosEnDisco();
            ResponseEntity<String> baja = rest.exchange("/v1/anuncios/" + id, HttpMethod.DELETE,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);
            assertThat(baja.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(anuncios.findById(UUID.fromString(id)).orElseThrow().getEstado()).isEqualTo(EstadoAnuncio.eliminado);
            assertThat(objetosEnDisco()).isEqualTo(objetosAntes);
        }

        @Test
        @DisplayName("Subidas rechazadas (422) no dejan objetos; sin sesión → 401")
        void rechazosSinHuerfanos() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-rechazo");
            long antes = objetosEnDisco();

            byte[][] seis = {JPEG, JPEG, JPEG, JPEG, JPEG, JPEG};
            ResponseEntity<String> demasiadas = crearAnuncio(vendedor.token(), "Seis fotos " + UUID.randomUUID(), "10", seis);
            assertThat(demasiadas.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            byte[][] falso = {"no soy imagen".getBytes()};
            ResponseEntity<String> tipoMalo = crearAnuncio(vendedor.token(), "Falso " + UUID.randomUUID(), "10", falso);
            assertThat(tipoMalo.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            ResponseEntity<String> sinSesion = crearAnuncio("", "Sin sesión " + UUID.randomUUID(), "10", JPEG);
            assertThat(sinSesion.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(objetosEnDisco()).isEqualTo(antes);
        }
    }

    @Nested
    @DisplayName("Reemplazo de imágenes (N6) y límites de búsqueda")
    class ImagenesYBuscador {

        @Test
        @DisplayName("PUT /imagenes: sustituye archivos (los viejos se borran) y resetea la revisión")
        void reemplazoDeImagenes() throws IOException {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-imagenes");
            String creado = crearAnuncio(vendedor.token(), "Camara " + UUID.randomUUID(), "95", JPEG).getBody();
            JsonNode cuerpo = json.readTree(creado);
            String id = cuerpo.get("id").asText();
            String urlVieja = cuerpo.get("imagenUrls").get(0).asText();
            aprobar(UUID.fromString(id), vendedor.id());
            Path archivoViejo = SoportePostgresIT.MEDIA_DIR.resolve(urlVieja.replaceFirst("^/media/", ""));
            assertThat(Files.exists(archivoViejo)).isTrue();

            MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
            partes.add("imagenes", new ByteArrayResource(JPEG) {
                @Override
                public String getFilename() {
                    return "nueva.jpg";
                }
            });
            HttpHeaders cabeceras = conSesion(vendedor.token());
            cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);

            ResponseEntity<String> respuesta = rest.exchange("/v1/anuncios/" + id + "/imagenes", HttpMethod.PUT,
                    new HttpEntity<>(partes, cabeceras), String.class);

            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode actualizado = json.readTree(respuesta.getBody());
            assertThat(actualizado.get("estadoRevision").asText()).isEqualTo("pendiente");
            assertThat(actualizado.get("imagenUrls")).hasSize(1);
            assertThat(actualizado.get("imagenUrls").get(0).asText()).isNotEqualTo(urlVieja);
            assertThat(Files.exists(archivoViejo)).as("la vieja se borra tras el commit").isFalse();

            SesionDePrueba intruso = usuarioConSesion("intruso-imagenes");
            ResponseEntity<String> ajeno = rest.exchange("/v1/anuncios/" + id + "/imagenes", HttpMethod.PUT,
                    new HttpEntity<>(partes, conSesion(intruso.token())), String.class);
            assertThat(ajeno.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("q con más de 200 caracteres → 400 estructural")
        void qDemasiadoLargo() {
            ResponseEntity<String> respuesta = rest.getForEntity(
                    "/v1/anuncios?q=" + "x".repeat(201), String.class);

            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Lecturas del panel")
    class Panel {

        @Test
        @DisplayName("Mis anuncios: offset con total y solo las filas propias (R7)")
        void misAnuncios() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-panel");
            crearAnuncio(vendedor.token(), "Panel A " + UUID.randomUUID(), "10", JPEG);
            crearAnuncio(vendedor.token(), "Panel B " + UUID.randomUUID(), "20", JPEG);

            ResponseEntity<String> respuesta = rest.exchange("/v1/mis-anuncios", HttpMethod.GET,
                    new HttpEntity<>(null, conSesion(vendedor.token())), String.class);

            JsonNode cuerpo = json.readTree(respuesta.getBody());
            assertThat(cuerpo.get("datos").isArray()).isTrue();
            assertThat(cuerpo.get("datos").size()).isGreaterThanOrEqualTo(2);
            assertThat(cuerpo.get("paginacion").get("total").asLong()).isGreaterThanOrEqualTo(2);
            assertThat(cuerpo.get("datos").get(0).has("estadoRevision")).isTrue();
        }
    }

    @Nested
    @DisplayName("Nombre público del vendedor (R17)")
    class NombreDelVendedor {

        @Test
        @DisplayName("Catálogo y detalle publican nombre de pila + inicial del apellido, nunca el nombre completo")
        void nombrePublicoEnListadoYDetalle() {
            SesionDePrueba ana = usuarioConSesion("vendedora-ana", "Ana María Pérez Gómez");
            SesionDePrueba luis = usuarioConSesion("vendedor-luis", "Luis Gómez");
            String etiqueta = "R17 " + UUID.randomUUID();
            UUID deAna = UUID.fromString(json.readTree(
                    crearAnuncio(ana.token(), etiqueta + " A", "10", JPEG).getBody()).get("id").asText());
            UUID deLuis = UUID.fromString(json.readTree(
                    crearAnuncio(luis.token(), etiqueta + " B", "20", JPEG).getBody()).get("id").asText());
            aprobar(deAna, luis.id());
            aprobar(deLuis, ana.id());

            JsonNode pagina = json.readTree(rest.getForEntity(
                    "/v1/anuncios?q=" + etiqueta, String.class).getBody());
            JsonNode detalleAna = json.readTree(rest.getForEntity("/v1/anuncios/" + deAna, String.class).getBody());

            assertThat(pagina.get("datos")).hasSize(2);
            assertThat(pagina.get("datos").get(0).get("vendedorNombre").asText()).isIn("Ana G.", "Luis G.");
            assertThat(pagina.get("datos").get(1).get("vendedorNombre").asText()).isIn("Ana G.", "Luis G.");
            assertThat(detalleAna.get("vendedorNombre").asText()).isEqualTo("Ana G.");
            assertThat(rest.getForEntity("/v1/anuncios/" + deAna, String.class).getBody())
                    .doesNotContain("Pérez")
                    .doesNotContain("María");
        }

        @Test
        @DisplayName("Vendedor dado de baja → genérico \"Vendedor\" sin revelar el estado de la cuenta (R1/R15)")
        void vendedorDadoDeBaja() {
            SesionDePrueba vendedor = usuarioConSesion("vendedor-baja", "Ana Pérez");
            String nombre = "R17 baja " + UUID.randomUUID();
            UUID anuncioId = UUID.fromString(json.readTree(
                    crearAnuncio(vendedor.token(), nombre, "10", JPEG).getBody()).get("id").asText());
            aprobar(anuncioId, vendedor.id());
            jdbc.update("UPDATE usuarios SET fecha_eliminacion = now() WHERE id = ?", vendedor.id());

            JsonNode detalle = json.readTree(rest.getForEntity("/v1/anuncios/" + anuncioId, String.class).getBody());

            assertThat(detalle.get("vendedorNombre").asText()).isEqualTo("Vendedor");
        }
    }
}
