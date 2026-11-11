package com.unimarket.api.identity;

import com.unimarket.api.notifications.CorreoTransaccional;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Límite de intentos del PIN (R11, issue #8): cada fallo tiene que **persistir**, y el quinto
 * invalida el código. La versión anterior incrementaba el contador dentro de una transacción
 * que la propia excepción de negocio revertía: el máximo de 5 intentos no existía y el PIN
 * seguía siendo válido indefinidamente.
 */
@Tag("integration")
@DisplayName("PIN: máximo de 5 intentos persistidos (R11)")
class PinIntentosIntegrationTest extends SoportePostgresIT {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ServicioRegistro registro;

    @Autowired
    private ServicioRecuperacion recuperacion;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CorreoTransaccional correos;

    private static String emailUnico(String prefijo) {
        return prefijo + "+" + UUID.randomUUID() + "@correo.com";
    }

    private void registrar(String email) {
        assertThat(http.postForEntity("/v1/usuarios",
                Map.of("email", email, "nombre", "Ana PIN", "contrasena", "secreta123"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String pinDe(String email, String asunto) {
        ArgumentCaptor<CorreoTransaccional.MensajeCorreo> captor =
                ArgumentCaptor.forClass(CorreoTransaccional.MensajeCorreo.class);
        await().untilAsserted(() -> verify(correos, atLeastOnce()).enviar(captor.capture()));
        String texto = captor.getAllValues().stream()
                .filter(m -> m.destino().equals(email) && m.asunto().startsWith(asunto))
                .map(CorreoTransaccional.MensajeCorreo::texto)
                .reduce((primero, ultimo) -> ultimo)
                .orElseThrow(() -> new AssertionError("sin correo para " + email));
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(texto);
        assertThat(matcher.find()).as("el correo trae un PIN de 6 dígitos").isTrue();
        return matcher.group(1);
    }

    private static String otroPin(String pin) {
        return pin.equals("000000") ? "000001" : "000000";
    }

    private int intentos(String tabla, String email) {
        return jdbc.queryForObject("SELECT t.intentos FROM " + tabla + " t "
                + "JOIN usuarios u ON u.id = t.usuario_id WHERE u.email = ?", Integer.class, email);
    }

    private boolean usado(String tabla, String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT t.fecha_uso IS NOT NULL FROM " + tabla + " t "
                + "JOIN usuarios u ON u.id = t.usuario_id WHERE u.email = ?", Boolean.class, email));
    }

    @Test
    @DisplayName("Confirmación: cada fallo persiste y el quinto invalida el PIN")
    void confirmacion_cincoFallosInvalidanElPin() {
        String email = emailUnico("pin-conf");
        registrar(email);
        String normalizado = email.toLowerCase();
        String pin = pinDe(normalizado, "Confirma tu cuenta");

        for (int intento = 1; intento <= 5; intento++) {
            int esperado = intento;
            assertThatThrownBy(() -> registro.confirmarEmail(normalizado, otroPin(pin)))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThat(intentos("tokens_verificacion_email", normalizado))
                    .as("intentos persistidos tras el fallo %d", esperado).isEqualTo(esperado);
        }

        assertThat(usado("tokens_verificacion_email", normalizado))
                .as("el quinto fallo consume el PIN").isTrue();
        assertThatThrownBy(() -> registro.confirmarEmail(normalizado, pin))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage(ServicioRegistro.PIN_INVALIDO);
    }

    @Test
    @DisplayName("Reset: cada fallo persiste y el quinto invalida el PIN")
    void reset_cincoFallosInvalidanElPin() {
        String email = emailUnico("pin-reset");
        registrar(email);
        String normalizado = email.toLowerCase();
        assertThat(http.postForEntity("/v1/auth/restablecer-contrasena",
                Map.of("email", normalizado), String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        String pin = pinDe(normalizado, "Restablece tu contraseña");

        for (int intento = 1; intento <= 5; intento++) {
            int esperado = intento;
            assertThatThrownBy(() -> recuperacion.confirmar(normalizado, otroPin(pin), "nueva-clave-1"))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThat(intentos("tokens_restablecimiento_contrasena", normalizado))
                    .as("intentos persistidos tras el fallo %d", esperado).isEqualTo(esperado);
        }

        assertThat(usado("tokens_restablecimiento_contrasena", normalizado))
                .as("el quinto fallo consume el PIN").isTrue();
        assertThatThrownBy(() -> recuperacion.confirmar(normalizado, pin, "nueva-clave-1"))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage(ServicioRegistro.PIN_INVALIDO);
    }
}
