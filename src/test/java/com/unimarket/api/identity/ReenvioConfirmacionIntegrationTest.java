package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reenvío de confirmación con cooldown (PRD §5, N5) contra PostgreSQL 18 real:
 * respuesta genérica idéntica (R13), cooldown que evita reemisiones, invalidación del
 * token previo al reemitir (R11) y rate limit (Q1).
 */
@DisplayName("Reenvío de confirmación (N5)")
class ReenvioConfirmacionIntegrationTest extends SoportePostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private ResponseEntity<String> registrar(String email) {
        return rest.postForEntity("/v1/usuarios",
                Map.of("email", email, "nombre", "Reenvío", "contrasena", "secreta123"), String.class);
    }

    private ResponseEntity<String> reenviar(String email) {
        return rest.postForEntity("/v1/auth/reenviar-confirmacion", Map.of("email", email), String.class);
    }

    private UUID idDe(String email) {
        return jdbc.queryForObject("SELECT id FROM usuarios WHERE email = ?", UUID.class, email);
    }

    private String hashActivo(UUID usuarioId) {
        return jdbc.queryForObject(
                "SELECT hash_token FROM tokens_verificacion_email WHERE usuario_id = ? AND fecha_uso IS NULL",
                String.class, usuarioId);
    }

    @Test
    @DisplayName("Dentro del cooldown no reemite; tras el cooldown emite token nuevo e invalida el previo")
    void cooldownEInvalidacion() {
        String email = "reenvio-" + UUID.randomUUID() + "@uni.edu";
        assertThat(registrar(email).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID usuarioId = idDe(email);
        String hashInicial = hashActivo(usuarioId);

        assertThat(reenviar(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(hashActivo(usuarioId)).as("cooldown: el token no cambia").isEqualTo(hashInicial);

        // Retrasa la emisión inicial más allá del cooldown (2 min por defecto).
        jdbc.update("UPDATE tokens_verificacion_email SET fecha_creacion = now() - interval '5 minutes' "
                + "WHERE usuario_id = ? AND fecha_uso IS NULL", usuarioId);

        assertThat(reenviar(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String hashNuevo = hashActivo(usuarioId);
        assertThat(hashNuevo).as("token reemitido").isNotEqualTo(hashInicial);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tokens_verificacion_email WHERE usuario_id = ? AND fecha_uso IS NULL",
                Integer.class, usuarioId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Respuesta genérica idéntica: inexistente, ya confirmado y no confirmado responden igual")
    void respuestaGenerica() {
        String confirmado = "confirmado-" + UUID.randomUUID() + "@uni.edu";
        assertThat(registrar(confirmado).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        jdbc.update("UPDATE usuarios SET fecha_verificacion_email = now() WHERE email = ?", confirmado);

        String pendiente = "pendiente-" + UUID.randomUUID() + "@uni.edu";
        assertThat(registrar(pendiente).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> r1 = reenviar("no-existe-" + UUID.randomUUID() + "@uni.edu");
        ResponseEntity<String> r2 = reenviar(confirmado);
        ResponseEntity<String> r3 = reenviar(pendiente);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r2.getBody()).isEqualTo(r1.getBody());
        assertThat(r3.getBody()).isEqualTo(r1.getBody());
    }

    @Test
    @DisplayName("Rate limit: sexto intento en un minuto → 429")
    void rateLimit() {
        String email = "limite-" + UUID.randomUUID() + "@uni.edu";
        for (int intento = 0; intento < 5; intento++) {
            assertThat(reenviar(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(reenviar(email).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
