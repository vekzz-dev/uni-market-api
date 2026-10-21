package com.unimarket.api.catalog;

import com.unimarket.api.shared.pagination.PaginacionCursor;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Forma exacta del wire del catálogo (openapi.yaml): el listado publica
 * {@code AnuncioResumen} y el detalle {@code AnuncioDetalle}. Un campo de más no rompe
 * el build, pero el cliente generado desde el contrato lo descarta en silencio:
 * el contrato es la fuente de verdad y el runtime debe publicar lo que declara.
 */
@WebMvcTest(ControladorAnuncios.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ContratoCatalogoSliceTest.ResolverPrincipal.class)
@DisplayName("Catálogo — forma del contrato en el wire")
class ContratoCatalogoSliceTest {

    private static final UUID ANUNCIO_ID = UUID.fromString("d2c361c1-2786-422e-b5f5-a4419caa33f3");
    private static final UUID VENDEDOR_ID = UUID.fromString("264a17e3-6a47-48b3-88de-229527524676");
    private static final Instant INSTANTE = Instant.parse("2026-10-20T23:11:59Z");

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

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ServicioAnuncios anuncios;

    @MockitoBean
    private ServicioCatalogo catalogo;

    @Test
    @DisplayName("El listado no publica estado ni fechaActualizacion; el detalle sí los publica")
    void listadoYDetalleRespetanLosEsquemasDelContrato() throws Exception {
        DtosAnuncios.AnuncioResumen resumen = new DtosAnuncios.AnuncioResumen(
                ANUNCIO_ID, "Calculadora", new BigDecimal("45.00"), "descripción", null,
                List.of(), VENDEDOR_ID, INSTANTE);
        DtosAnuncios.AnuncioDetalle detalle = new DtosAnuncios.AnuncioDetalle(
                ANUNCIO_ID, "Calculadora", new BigDecimal("45.00"), "descripción", null,
                List.of(), VENDEDOR_ID, INSTANTE, EstadoAnuncio.disponible, INSTANTE);
        when(catalogo.buscar(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaColeccion<>(List.of(resumen), new PaginacionCursor(null)));
        when(catalogo.detalle(any())).thenReturn(detalle);

        JsonNode listado = json.readTree(cuerpoDe("/v1/anuncios")).get("datos").get(0);

        assertThat(listado.size())
                .as("AnuncioResumen declara 8 campos y el listado no puede publicar más")
                .isEqualTo(8);
        assertThat(listado.has("estado"))
                .as("estado no pertenece al resumen: el catálogo solo expone anuncios visibles")
                .isFalse();
        assertThat(listado.has("fechaActualizacion"))
                .as("fechaActualizacion no pertenece al resumen")
                .isFalse();

        JsonNode cuerpoDetalle = json.readTree(cuerpoDe("/v1/anuncios/" + ANUNCIO_ID));

        assertThat(cuerpoDetalle.size())
                .as("AnuncioDetalle = AnuncioResumen + estado + fechaActualizacion")
                .isEqualTo(10);
        assertThat(cuerpoDetalle.get("estado").asText()).isEqualTo("disponible");
        assertThat(cuerpoDetalle.has("fechaActualizacion")).isTrue();
    }

    private String cuerpoDe(String uri) throws Exception {
        return mvc.get().uri(uri).exchange().getResponse().getContentAsString();
    }
}
