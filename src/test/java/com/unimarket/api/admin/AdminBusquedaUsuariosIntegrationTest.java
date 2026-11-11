package com.unimarket.api.admin;

import com.unimarket.api.identity.SoportePostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Búsqueda de usuarios del panel (issue #7, R15) contra PostgreSQL 18 real: {@code q} en un
 * parámetro aditivo que busca en nombre y email con escape de comodines y se compone con `rol`.
 *
 * <p>Los términos llevan sufijo único: el contenedor y el contexto son singletons compartidos
 * por toda la suite, así que un nombre "real" como {@code ana} encuentra cuentas sembradas por
 * otros tests y vuelve la aserción dependiente del orden de ejecución.
 */
@DisplayName("Admin — búsqueda de usuarios (issue #7)")
class AdminBusquedaUsuariosIntegrationTest extends SoportePostgresIT {

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

    private Sesion usuario(String etiqueta, String rol, String nombre) {
        String email = etiqueta.toLowerCase() + "-" + UUID.randomUUID() + "@uni.edu";
        UUID id = jdbc.queryForObject(
                "INSERT INTO usuarios (email, nombre, hash_contrasena, fecha_verificacion_email, rol) "
                        + "VALUES (?, ?, ?, now(), ?) RETURNING id",
                UUID.class, email, nombre, encoder.encode("secreta123"), rol);
        ResponseEntity<String> login = rest.postForEntity(
                "/v1/auth/login", Map.of("email", email, "contrasena", "secreta123"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Sesion(id, json.readTree(login.getBody()).get("accessToken").asText());
    }

    private ResponseEntity<String> get(String token, Map<String, String> parametros) {
        UriComponentsBuilder constructor = UriComponentsBuilder.fromPath("/v1/admin/usuarios");
        parametros.forEach(constructor::queryParam);
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(token);
        return rest.exchange(constructor.encode().build().toUri(), HttpMethod.GET,
                new HttpEntity<>(null, cabeceras), String.class);
    }

    private JsonNode cuerpo(String token, Map<String, String> parametros) {
        ResponseEntity<String> respuesta = get(token, parametros);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(respuesta.getBody());
    }

    private static long total(JsonNode cuerpo) {
        return cuerpo.get("paginacion").get("total").asLong();
    }

    private static boolean contiene(JsonNode cuerpo, UUID id) {
        return cuerpo.get("datos").toString().contains(id.toString());
    }

    /** Sufijo irrepetible que no colisiona con datos de otras suites. */
    private static String sufijo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Busca por nombre y por email sin distinguir mayúsculas, y el total refleja el filtro")
    void buscaPorNombreYEmail() {
        Sesion admin = usuario("admin-busca", "admin", "Admin Busca");
        String sufijo = sufijo();
        Sesion ana = usuario("busca-ana-" + sufijo, "miembro", "Ana María " + sufijo.toUpperCase());
        Sesion luis = usuario("busca-luis-" + sufijo, "miembro", "Luis " + sufijo.toUpperCase());

        JsonNode porNombre = cuerpo(admin.token(), Map.of("limite", "100", "q", "ana maría " + sufijo));
        JsonNode porEmail = cuerpo(admin.token(), Map.of("limite", "100", "q", "BUSCA-ANA-" + sufijo.toUpperCase()));

        assertThat(contiene(porNombre, ana.id())).as("búsqueda sin distinguir mayúsculas").isTrue();
        assertThat(contiene(porNombre, luis.id())).isFalse();
        assertThat(total(porNombre)).isEqualTo(1);
        assertThat(contiene(porEmail, ana.id())).as("también busca en el email").isTrue();
        assertThat(total(porEmail)).isEqualTo(1);
    }

    @Test
    @DisplayName("Los comodines %, _ y \\ se buscan literales")
    void comodinesLiterales() {
        Sesion admin = usuario("admin-comodin", "admin", "Admin Comodin");
        String sufijo = sufijo();
        Sesion porcentaje = usuario("comodin-pct", "miembro", "Descuento " + sufijo + " 100% Real");
        Sesion guionBajo = usuario("comodin-sub", "miembro", "Nombre_" + sufijo + "_guion");
        Sesion barra = usuario("comodin-barra", "miembro", "Nombre\\" + sufijo);

        assertThat(total(cuerpo(admin.token(), Map.of("limite", "100", "q", sufijo + " 100%")))).isEqualTo(1);

        long todos = total(cuerpo(admin.token(), Map.of("limite", "1")));
        JsonNode soloPorcentaje = cuerpo(admin.token(), Map.of("limite", "100", "q", "%"));
        JsonNode soloGuionBajo = cuerpo(admin.token(), Map.of("limite", "100", "q", "_"));
        JsonNode soloBarra = cuerpo(admin.token(), Map.of("limite", "100", "q", "\\"));

        assertThat(total(soloPorcentaje)).as("q=%% literal no devuelve todo").isLessThan(todos);
        assertThat(contiene(soloPorcentaje, porcentaje.id())).isTrue();
        assertThat(contiene(soloPorcentaje, barra.id())).as("no matchea nombres sin %%").isFalse();
        assertThat(total(soloGuionBajo)).as("q=_ literal no devuelve todo").isLessThan(todos);
        assertThat(contiene(soloGuionBajo, guionBajo.id())).isTrue();
        assertThat(total(soloBarra)).as("q=\\ literal no devuelve todo").isLessThan(todos);
        assertThat(contiene(soloBarra, barra.id())).isTrue();
    }

    @Test
    @DisplayName("Se combina con rol: los dos filtros aplican y la paginación refleja el filtro")
    void combinaConRol() {
        Sesion admin = usuario("admin-combina", "admin", "Admin Combina");
        String sufijo = sufijo();
        usuario("combina-uno-" + sufijo, "miembro", "Marta " + sufijo.toUpperCase());
        usuario("combina-dos-" + sufijo, "miembro", "Marta Dos " + sufijo.toUpperCase());
        usuario("combina-tres-" + sufijo, "miembro", "Marta Tres " + sufijo.toUpperCase());

        JsonNode coincide = cuerpo(admin.token(), Map.of("limite", "100", "q", sufijo, "rol", "miembro"));
        JsonNode descarta = cuerpo(admin.token(), Map.of("limite", "100", "q", sufijo, "rol", "admin"));
        JsonNode paginado = cuerpo(admin.token(), Map.of("limite", "1", "q", sufijo));

        assertThat(total(coincide)).isEqualTo(3);
        assertThat(total(descarta)).isZero();
        assertThat(total(paginado)).isEqualTo(3);
        assertThat(paginado.get("paginacion").get("totalPaginas").asInt()).isEqualTo(3);
        assertThat(paginado.get("datos").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Las bajas quedan fuera, q en blanco no filtra y 201 caracteres responden 400")
    void limitesDeQ() {
        Sesion admin = usuario("admin-limites", "admin", "Admin Limites");
        Sesion baja = usuario("baja-busca", "miembro", "Zoe Retirada " + UUID.randomUUID());
        usuario("limites-uno", "miembro", "Pablo Estable");
        jdbc.update("UPDATE usuarios SET fecha_eliminacion = now() WHERE id = ?", baja.id());

        long sinQ = total(cuerpo(admin.token(), Map.of("limite", "1")));

        assertThat(total(cuerpo(admin.token(), Map.of("limite", "1", "q", "")))).isEqualTo(sinQ);
        assertThat(total(cuerpo(admin.token(), Map.of("limite", "1", "q", "   ")))).isEqualTo(sinQ);
        assertThat(total(cuerpo(admin.token(), Map.of("limite", "100", "q", "Zoe Retirada")))).isZero();

        ResponseEntity<String> demasiadoLarga = get(admin.token(), Map.of("q", "x".repeat(201)));

        assertThat(demasiadoLarga.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(demasiadoLarga.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(demasiadoLarga.getBody()).contains("El parámetro 'q' admite hasta 200 caracteres.");
        assertThat(get(admin.token(), Map.of("q", "x".repeat(200))).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("V5 crea los dos GIN trigram sobre lower(nombre) y lower(email)")
    void indicesTrigram() {
        List<String> definiciones = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname IN "
                        + "('idx_usuarios_nombre_trgm', 'idx_usuarios_email_trgm')", String.class);

        assertThat(definiciones).hasSize(2);
        assertThat(definiciones).anySatisfy(definicion -> assertThat(definicion)
                .contains("USING gin").contains("lower").contains("nombre").contains("gin_trgm_ops"));
        assertThat(definiciones).anySatisfy(definicion -> assertThat(definicion)
                .contains("USING gin").contains("lower").contains("email").contains("gin_trgm_ops"));
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
    }
}
