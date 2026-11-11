package com.unimarket.api.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redirección de correos en local ({@code NOTIFICACIONES_DESTINO_PRUEBA}): con un dominio
 * sin verificar, el proveedor solo entrega a la bandeja dueña de la cuenta, así que el
 * oyente desvía TODOS los correos a una sola dirección para comprobar la entrega real.
 * El destinatario original viaja en el asunto para no perder el rastro.
 */
@DisplayName("OyenteCorreos — redirección de pruebas")
class OyenteCorreosRedireccionTest {

    /** Puerto falso: guarda lo que se habría enviado. */
    private static class CorreoDePrueba implements CorreoTransaccional {

        private final List<MensajeCorreo> enviados = new ArrayList<>();

        @Override
        public void enviar(MensajeCorreo mensaje) {
            enviados.add(mensaje);
        }
    }

    private static PropiedadesNotificaciones propiedades(String destinoDePrueba) {
        return new PropiedadesNotificaciones("resend+log", null, destinoDePrueba, null);
    }

    private static EventosCorreo.ConfirmacionRequerida confirmacion() {
        return new EventosCorreo.ConfirmacionRequerida(UUID.randomUUID(), "ana@uni.edu", "token-123");
    }

    @Test
    @DisplayName("Sin redirección el correo va al destinatario real")
    void sinRedireccion() {
        CorreoDePrueba port = new CorreoDePrueba();
        OyenteCorreos oyente = new OyenteCorreos(port, propiedades(""));

        oyente.alConfirmar(confirmacion());

        assertThat(port.enviados).hasSize(1);
        assertThat(port.enviados.get(0).destino()).isEqualTo("ana@uni.edu");
        assertThat(port.enviados.get(0).asunto()).doesNotContain("[para");
    }

    @Test
    @DisplayName("Con redirección todo va a la bandeja de pruebas y el original queda en el asunto")
    void conRedireccion() {
        CorreoDePrueba port = new CorreoDePrueba();
        OyenteCorreos oyente = new OyenteCorreos(port, propiedades("  ia.fm100224@ufg.edu.sv  "));

        oyente.alConfirmar(confirmacion());

        assertThat(port.enviados).hasSize(1);
        assertThat(port.enviados.get(0).destino()).isEqualTo("ia.fm100224@ufg.edu.sv");
        assertThat(port.enviados.get(0).asunto())
                .startsWith("[para ana@uni.edu]")
                .contains("Confirma tu cuenta");
        assertThat(port.enviados.get(0).texto()).as("el token no se toca").contains("token-123");
    }

    @Test
    @DisplayName("La redirección también aplica al restablecimiento de contraseña")
    void redirigeTambienElReset() {
        CorreoDePrueba port = new CorreoDePrueba();
        OyenteCorreos oyente = new OyenteCorreos(port, propiedades("pruebas@uni.edu"));

        oyente.alRestablecer(new EventosCorreo.RestablecimientoRequerido(
                UUID.randomUUID(), "luis@uni.edu", "token-456"));

        assertThat(port.enviados.get(0).destino()).isEqualTo("pruebas@uni.edu");
        assertThat(port.enviados.get(0).asunto()).startsWith("[para luis@uni.edu]");
    }
}
