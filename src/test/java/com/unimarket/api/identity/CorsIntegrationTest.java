package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS por configuración (SDD §8): el origen permitido recibe las cabeceras (incluido el
 * preflight OPTIONS que usan los navegadores y Hoppscotch web), y un origen ajeno NO
 * recibe {@code Access-Control-Allow-Origin}. Config de test: solo {@code localhost:5173}.
 */
@DisplayName("CORS — configuración por entorno")
class CorsIntegrationTest extends SoportePostgresIT {

    @Autowired
    private TestRestTemplate rest;

    private static HttpHeaders conOrigen(String origen, String metodoSolicitado) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setOrigin(origen);
        if (metodoSolicitado != null) {
            cabeceras.setAccessControlRequestMethod(HttpMethod.valueOf(metodoSolicitado));
        }
        return cabeceras;
    }

    @Test
    @DisplayName("Preflight OPTIONS del origen permitido → 200 con cabeceras CORS")
    void preflightPermitido() {
        ResponseEntity<String> respuesta = rest.exchange("/v1/usuarios", HttpMethod.OPTIONS,
                new HttpEntity<>(conOrigen("http://localhost:5173", "POST")), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
        assertThat(respuesta.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST);
    }

    @Test
    @DisplayName("Petición real con origen permitido expone Location y Allow-Origin")
    void origenPermitidoEnRespuesta() {
        ResponseEntity<String> respuesta = rest.exchange("/v1/categorias", HttpMethod.GET,
                new HttpEntity<>(conOrigen("http://localhost:5173", null)), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
        assertThat(respuesta.getHeaders().getAccessControlExposeHeaders()).contains("Location");
    }

    @Test
    @DisplayName("Origen ajeno → sin cabeceras CORS (navegador bloqueado)")
    void origenAjenoBloqueado() {
        ResponseEntity<String> respuesta = rest.exchange("/v1/categorias", HttpMethod.GET,
                new HttpEntity<>(conOrigen("http://malicioso.example.com", null)), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(respuesta.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
