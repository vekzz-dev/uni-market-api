package com.unimarket.api.shared.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Errores RFC 9457 del handler global: estados, content type {@code application/problem+json},
 * presencia de {@code title}/{@code status} y no fuga del detalle interno en el 500.
 *
 * <p>Slice {@code @WebMvcTest} con un controlador de test exclusivo y sin filtros de
 * seguridad: las respuestas se verifican con {@link MockMvcTester} (AssertJ, SDD §9).
 */
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ApiExceptionHandler")
class ApiExceptionHandlerTest {

    private static final String MENSAJE_INTERNO = "detalle interno que no debe filtrarse";

    @Autowired
    private MockMvcTester mvc;

    @Nested
    @DisplayName("Errores de dominio")
    class ErroresDeDominio {

        @Test
        @DisplayName("404: NoEncontradoException con instance = URI del request")
        void noEncontrado_shouldReturnProblem404() {
            assertThat(mvc.get().uri("/prueba/no-encontrado"))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.title").asString().isEqualTo("Recurso no encontrado");
        }

        @Test
        @DisplayName("409: ConflictoException")
        void conflicto_shouldReturnProblem409() {
            assertThat(mvc.get().uri("/prueba/conflicto"))
                    .hasStatus(HttpStatus.CONFLICT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.title").asString().isEqualTo("Conflicto con el estado actual");
        }

        @Test
        @DisplayName("422: ReglaDeNegocioException (regla de negocio, no estructura)")
        void reglaDeNegocio_shouldReturnProblem422() {
            assertThat(mvc.get().uri("/prueba/regla-de-negocio"))
                    .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.title").asString().isEqualTo("Regla de negocio violada");
        }

        @Test
        @DisplayName("403: ProhibidoException")
        void prohibido_shouldReturnProblem403() {
            assertThat(mvc.get().uri("/prueba/prohibido"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.title").asString().isEqualTo("Acción no permitida");
        }

        @Test
        @DisplayName("400: EstructuraInvalidaException")
        void estructuraInvalida_shouldReturnProblem400() {
            assertThat(mvc.get().uri("/prueba/estructura-invalida"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.title").asString().isEqualTo("Solicitud inválida");
        }
    }

    @Nested
    @DisplayName("Errores estructurales (400)")
    class ErroresEstructurales {

        @Test
        @DisplayName("Bean Validation: une los campos inválidos en el detalle")
        void cuerpoInvalido_shouldReturnProblem400WithFieldDetail() {
            assertThat(mvc.post().uri("/prueba/validacion")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nombre\":\"\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.detail").asString().contains("nombre");
        }

        @Test
        @DisplayName("JSON malformado → 400, no 500")
        void cuerpoMalformado_shouldReturnProblem400() {
            assertThat(mvc.post().uri("/prueba/validacion")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nombre\": "))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status");
        }

        @Test
        @DisplayName("Parámetro con tipo inválido → 400 con el nombre del parámetro")
        void parametroInvalido_shouldReturnProblem400() {
            assertThat(mvc.get().uri("/prueba/parametro").param("limite", "no-es-numero"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.detail").asString().contains("limite");
        }
    }

    @Nested
    @DisplayName("Fallback 500")
    class Fallback {

        @Test
        @DisplayName("Excepción no controlada → 500 con detalle genérico, sin el mensaje interno")
        void excepcionNoControlada_shouldReturnGenericProblem500() {
            assertThat(mvc.get().uri("/prueba/inesperado"))
                    .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status")
                    .extractingPath("$.detail").asString().isEqualTo(ApiExceptionHandler.DETALLE_500);

            assertThat(mvc.get().uri("/prueba/inesperado"))
                    .bodyText()
                    .doesNotContain(MENSAJE_INTERNO, "IllegalStateException");
        }
    }

    @Nested
    @DisplayName("Rutas no mapeadas")
    class RutasNoMapeadas {

        @Test
        @DisplayName("Ruta sin handler → 404 problem+json, no 500")
        void rutaNoMapeada_shouldReturnProblem404() {
            assertThat(mvc.get().uri("/ruta-que-no-existe"))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .hasPath("$.title")
                    .hasPath("$.status");
        }
    }

    /** Controlador exclusivo del test: dispara cada tipo de error de forma controlada. */
    @RestController
    @RequestMapping("/prueba")
    static class ControladorDePrueba {

        record CuerpoDePrueba(@NotBlank String nombre) {
        }

        @GetMapping("/no-encontrado")
        void noEncontrado() {
            throw new NoEncontradoException("el anuncio no existe o no es visible");
        }

        @GetMapping("/conflicto")
        void conflicto() {
            throw new ConflictoException("el email ya está registrado");
        }

        @GetMapping("/regla-de-negocio")
        void reglaDeNegocio() {
            throw new ReglaDeNegocioException("el motivo no puede estar vacío");
        }

        @GetMapping("/prohibido")
        void prohibido() {
            throw new ProhibidoException("no puedes decidir sobre tu propio anuncio");
        }

        @GetMapping("/estructura-invalida")
        void estructuraInvalida() {
            throw new EstructuraInvalidaException("el cursor no es válido");
        }

        @PostMapping("/validacion")
        void validacion(@Valid @RequestBody CuerpoDePrueba cuerpo) {
        }

        @GetMapping("/parametro")
        void parametro(@RequestParam int limite) {
        }

        @GetMapping("/inesperado")
        void inesperado() {
            throw new IllegalStateException(MENSAJE_INTERNO);
        }
    }
}
