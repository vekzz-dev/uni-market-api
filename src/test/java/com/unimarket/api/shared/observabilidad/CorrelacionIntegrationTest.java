package com.unimarket.api.shared.observabilidad;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correlación de requests (SDD §10): el {@code X-Request-Id} entrante se respeta, uno
 * ausente o inseguro se reemplaza por un UUID, y siempre vuelve en la respuesta para que
 * el cliente pueda reportar un identificador accionable.
 */
@DisplayName("Correlación traceId — flujo HTTP")
class CorrelacionIntegrationTest extends SoportePostgresIT {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> get(String requestId) {
        HttpHeaders cabeceras = new HttpHeaders();
        if (requestId != null) {
            cabeceras.add(FiltroCorrelacion.CABECERA, requestId);
        }
        return rest.exchange("/v1/categorias", HttpMethod.GET, new HttpEntity<>(cabeceras), String.class);
    }

    @Test
    @DisplayName("El id entrante seguro se respeta y vuelve en la respuesta")
    void idEntranteSeRespeta() {
        ResponseEntity<String> respuesta = get("prueba-123_ABC.def");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getHeaders().getFirst(FiltroCorrelacion.CABECERA)).isEqualTo("prueba-123_ABC.def");
    }

    @Test
    @DisplayName("Sin cabecera se genera un UUID y también vuelve")
    void sinCabeceraGeneraUuid() {
        String id = get(null).getHeaders().getFirst(FiltroCorrelacion.CABECERA);

        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Un id inseguro (inyección de líneas o demasiado largo) se descarta y se reemplaza")
    void idInseguroSeDescarta() {
        ResponseEntity<String> conSalto = get("malicioso\ninject: true");
        ResponseEntity<String> larguisimo = get("x".repeat(200));

        assertThat(conSalto.getHeaders().getFirst(FiltroCorrelacion.CABECERA))
                .doesNotContain("malicioso").doesNotContain("\n");
        assertThat(larguisimo.getHeaders().getFirst(FiltroCorrelacion.CABECERA)).hasSizeLessThanOrEqualTo(64);
    }
}
