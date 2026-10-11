package com.unimarket.api.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificación del esquema V1–V3 (DBDD §6) contra PostgreSQL 18 real.
 *
 * <p>No ejecuta Flyway a mano: el contexto de la aplicación arranca con la
 * configuración real de {@code application.yml} ({@code spring.flyway.enabled=true}),
 * de modo que el test también verifica el cableado de la app. El DataSource apunta al
 * contenedor mediante {@code @ServiceConnection} (sin sustitutos en memoria: PRD §6).
 *
 * <p>Se verifican los objetos POR NOMBRE. En {@code pg_indexes} convive un número mayor
 * de índices (los de PK y de constraints UNIQUE no forman parte de los 16 de §3.1), por
 * eso no se assertion-a un conteo total de índices.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Esquema de BD: Flyway V1–V3 sobre PostgreSQL 18 (Testcontainers)")
class DbSchemaVerificationTest {

    /** Las 8 tablas del DBDD §1: 5 de negocio + 3 de tokens. */
    private static final List<String> TABLAS_ESPERADAS = List.of(
            "usuarios",
            "categorias",
            "anuncios",
            "conversaciones",
            "mensajes",
            "tokens_verificacion_email",
            "tokens_restablecimiento_contrasena",
            "tokens_refresh");

    /** Los 16 índices nombrados del DBDD §3.1: 13 en V1 + 3 parciales de tokens en V3. */
    private static final List<String> INDICES_ESPERADOS = List.of(
            "idx_anuncios_vendedor",
            "idx_anuncios_categoria",
            "idx_anuncios_estado_revision",
            "idx_anuncios_publicacion",
            "idx_anuncios_nombre_trgm",
            "idx_anuncios_descripcion_trgm",
            "idx_conversaciones_comprador",
            "idx_conversaciones_vendedor",
            "idx_mensajes_conversacion_creado",
            "idx_mensajes_no_leidos",
            "idx_usuarios_rol",
            "ux_usuarios_email_activo",
            "idx_categorias_orden",
            "ux_tokens_email_activos",
            "ux_tokens_reset_activos",
            "ux_tokens_refresh_activos");

    /**
     * Los 13 CHECKs nombrados del DBDD §3.2 como pares {@code tabla.nombre}: son 14 filas
     * de {@code pg_constraint} porque {@code chk_nombre_no_vacio} existe en {@code usuarios}
     * y en {@code anuncios}.
     */
    private static final List<String> CHECKS_ESPERADOS = List.of(
            "usuarios.chk_nombre_no_vacio",
            "usuarios.usuarios_rol_check",
            "categorias.chk_icono_longitud",
            "anuncios.chk_nombre_no_vacio",
            "anuncios.chk_precio_rango",
            "anuncios.chk_max_imagenes",
            "anuncios.chk_imagenes_pareadas",
            "anuncios.anuncios_estado_check",
            "anuncios.anuncios_estado_revision_check",
            "anuncios.chk_revision_trail_consistente",
            "anuncios.chk_motivo_rechazo_obligatorio",
            "conversaciones.chk_conversacion_no_propia",
            "mensajes.chk_mensaje_no_vacio");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fila del catálogo de CHECKs: nombre de la restricción y tabla que la declara. */
    private record Chequeo(String tabla, String nombre) {

        String referencia() {
            return tabla + "." + nombre;
        }
    }

    @Nested
    @DisplayName("Migraciones y estructura")
    class Migraciones {

        @Test
        @DisplayName("El contenedor corre PostgreSQL 18")
        void motor_shouldBePostgreSQL18() {
            String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
            System.out.println("[evidencia] Motor de base de datos: " + version);

            assertThat(version).startsWith("PostgreSQL 18");
        }

        @Test
        @DisplayName("Flyway aplica V1, V2 y V3 desde la configuración de la aplicación")
        void flyway_shouldApplyThreeMigrations_whenContextStarts() {
            List<String> versiones = jdbcTemplate.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                    String.class);

            assertThat(versiones).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("Las 8 tablas del DBDD existen en information_schema (schema public)")
        void tablas_shouldExistAllEight_whenMigrationsApplied() {
            List<String> tablas = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                            + "AND table_name <> 'flyway_schema_history'",
                    String.class);

            System.out.println("[evidencia] Tablas: " + tablas.stream().sorted().toList());
            assertThat(tablas)
                    .as("tablas de negocio y de tokens del DBDD §1 (sin la historia de Flyway)")
                    .containsExactlyInAnyOrderElementsOf(TABLAS_ESPERADAS);
        }
    }

    @Nested
    @DisplayName("Índices (§3.1)")
    class Indices {

        @Test
        @DisplayName("Los 16 índices nombrados existen en pg_indexes (13 de V1 + 3 de V3)")
        void indices_shouldExistTheSixteenNamed_whenMigrationsApplied() {
            List<String> existentes = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

            List<String> encontrados = INDICES_ESPERADOS.stream().filter(existentes::contains).toList();
            System.out.println("[evidencia] Índices nombrados encontrados (" + encontrados.size() + "): " + encontrados);

            assertThat(existentes)
                    .as("índices nombrados del DBDD §3.1 presentes en pg_indexes")
                    .containsAll(INDICES_ESPERADOS);
        }
    }

    @Nested
    @DisplayName("CHECKs (§3.2)")
    class RestriccionesCheck {

        @Test
        @DisplayName("Los 13 CHECKs nombrados existen en pg_constraint (contype='c')")
        void checks_shouldExistTheThirteen_whenMigrationsApplied() {
            List<Chequeo> chequeos = chequeos();

            List<String> encontrados = chequeos.stream().map(Chequeo::referencia).sorted().toList();
            System.out.println("[evidencia] CHECKs encontrados (" + encontrados.size() + "): " + encontrados);

            assertThat(chequeos)
                    .as("catálogo de CHECKs del DBDD §3.2 por (tabla, nombre)")
                    .extracting(Chequeo::referencia)
                    .containsAll(CHECKS_ESPERADOS);
        }

        @Test
        @DisplayName("chk_nombre_no_vacio existe en usuarios Y en anuncios (mismo nombre, dos tablas)")
        void checkNombreNoVacio_shouldExistOnBothTables() {
            assertThat(chequeos())
                    .as("chk_nombre_no_vacio declarado en usuarios y en anuncios")
                    .extracting(Chequeo::referencia)
                    .contains("usuarios.chk_nombre_no_vacio", "anuncios.chk_nombre_no_vacio");
        }

        private List<Chequeo> chequeos() {
            return jdbcTemplate.query(
                    "SELECT conrelid::regclass::text AS tabla, conname AS nombre FROM pg_constraint "
                            + "WHERE contype = 'c' AND connamespace = 'public'::regnamespace "
                            + "ORDER BY tabla, nombre",
                    (fila, indice) -> new Chequeo(fila.getString("tabla"), fila.getString("nombre")));
        }
    }

    @Nested
    @DisplayName("Claves foráneas (§2)")
    class ClavesForaneas {

        @ParameterizedTest(name = "{0} → confdeltype=''{1}''")
        @CsvSource({
                "anuncios_vendedor_id_fkey, c",
                "anuncios_categoria_id_fkey, n",
                "anuncios_revisado_por_fkey, a",
                "conversaciones_anuncio_id_fkey, c",
                "conversaciones_comprador_id_fkey, c",
                "conversaciones_vendedor_id_fkey, c",
                "mensajes_conversacion_id_fkey, c",
                "mensajes_remitente_id_fkey, c"})
        @DisplayName("Las 8 FKs de negocio tienen el ON DELETE del ERD (c=CASCADE, n=SET NULL, a=NO ACTION)")
        void fkDeNegocio_shouldHaveExpectedDeleteBehaviour(String restriccion, String comportamientoEsperado) {
            assertThat(comportamientoDeBorrado(restriccion))
                    .as("confdeltype de %s", restriccion)
                    .isEqualTo(comportamientoEsperado);
        }

        @ParameterizedTest(name = "{0} → confdeltype=''a''")
        @CsvSource({
                "tokens_verificacion_email_usuario_id_fkey",
                "tokens_restablecimiento_contrasena_usuario_id_fkey",
                "tokens_refresh_usuario_id_fkey"})
        @DisplayName("Las FKs de tokens son NO ACTION explícitas: la baja soportada es soft-delete (UPDATE)")
        void fkDeTokens_shouldBeNoAction(String restriccion) {
            assertThat(comportamientoDeBorrado(restriccion))
                    .as("confdeltype de %s", restriccion)
                    .isEqualTo("a");
        }

        private String comportamientoDeBorrado(String restriccion) {
            return jdbcTemplate.queryForObject(
                    "SELECT confdeltype::text FROM pg_constraint "
                            + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace AND conname = ?",
                    String.class,
                    restriccion);
        }
    }

    @Nested
    @DisplayName("Sin RLS")
    class PoliticaDeAcceso {

        @Test
        @DisplayName("pg_policies está vacío: la autorización vive en la aplicación, no en la BD")
        void policies_shouldBeEmpty() {
            Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_policies", Integer.class);

            assertThat(total).isZero();
        }
    }

    @Nested
    @DisplayName("Semilla de categorías (§4)")
    class SemillaDeCategorias {

        @Test
        @DisplayName("6 categorías con orden 1–6 y sus emojis")
        void semilla_shouldInsertSixOrderedCategories_whenMigrationRuns() {
            List<Integer> ordenes = jdbcTemplate.queryForList(
                    "SELECT orden FROM categorias ORDER BY orden", Integer.class);
            List<String> nombres = jdbcTemplate.queryForList(
                    "SELECT nombre FROM categorias ORDER BY orden", String.class);

            assertThat(ordenes).containsExactly(1, 2, 3, 4, 5, 6);
            assertThat(nombres)
                    .containsExactly("Electrónica", "Libros", "Ropa", "Deportes", "Hogar", "Otros");
        }

        @Test
        @DisplayName("Idempotencia: re-ejecutar el INSERT de V2 no duplica categorías")
        void semilla_shouldBeIdempotent_whenInsertIsExecutedAgain() {
            assertThat(sentenciaDeSeed())
                    .as("V2 usa ON CONFLICT (nombre) DO NOTHING para ser idempotente (DBDD §6)")
                    .contains("ON CONFLICT (nombre) DO NOTHING");

            jdbcTemplate.execute(sentenciaDeSeed());

            Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM categorias", Integer.class);
            assertThat(total).as("categorías tras re-ejecutar el seed").isEqualTo(6);
        }

        /** Contenido de V2 sin las líneas de comentario, listo para re-ejecutarse. */
        private String sentenciaDeSeed() {
            String contenido;
            try (InputStream entrada = new ClassPathResource("db/migration/V2__datos_categorias.sql").getInputStream()) {
                contenido = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException excepcion) {
                throw new UncheckedIOException("No se pudo leer V2__datos_categorias.sql", excepcion);
            }
            return Arrays.stream(contenido.split("\n"))
                    .filter(linea -> !linea.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        }
    }
}
