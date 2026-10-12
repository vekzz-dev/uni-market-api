package com.unimarket.api.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validación estructural del wire de catalog (SDD §6): cuerpo inválido y parte obligatoria
 * ausente responden {@code 400} problem+json, jamás 500. Slice sin filtros de seguridad;
 * un resolver mínimo de {@code @AuthenticationPrincipal} permite invocar handlers con
 * {@code Jwt} como en la app real (la cadena de seguridad completa se cubre en el IT).
 */
@WebMvcTest(ControladorAnuncios.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ValidacionCatalogoSliceTest.ResolverPrincipal.class)
@DisplayName("Catálogo — validación web")
class ValidacionCatalogoSliceTest {

    /** Sin la configuración de seguridad, el resolver de principal no está registrado. */
    @TestConfiguration
    static class ResolverPrincipal implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ServicioAnuncios anuncios;

    @MockitoBean
    private ServicioCatalogo catalogo;

    @Test
    @DisplayName("PATCH con nombre de 300 caracteres → 400 problem+json")
    void nombreDemasiadoLargo() {
        String largo = "x".repeat(300);
        assertThat(mvc.patch().uri("/v1/anuncios/" + UUID.randomUUID() + "/contenido")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"" + largo + "\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("Multipart sin el campo obligatorio nombre → 400, no 500")
    void parteObligatoriaAusente() {
        assertThat(mvc.post().uri("/v1/anuncios")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .content(""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
