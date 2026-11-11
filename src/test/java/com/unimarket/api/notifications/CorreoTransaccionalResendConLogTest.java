package com.unimarket.api.notifications;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Modo local completo ({@code resend+log}): el correo sale de verdad por Resend y el token
 * queda además en el log. La invariante que importa es el orden: el log se escribe ANTES
 * del envío, así un fallo del proveedor no deja al usuario sin forma de confirmar.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CorreoTransaccionalResendConLog")
class CorreoTransaccionalResendConLogTest {

    private HttpServer servidor;

    @AfterEach
    void apagar() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private String levantarStub(int codigo, AtomicReference<String> cuerpo, AtomicInteger peticiones) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(0), 0);
        servidor.createContext("/emails", intercambio -> {
            peticiones.incrementAndGet();
            cuerpo.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(codigo, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        servidor.start();
        return "http://localhost:" + servidor.getAddress().getPort() + "/emails";
    }

    private CorreoTransaccionalResendConLog adaptador(String baseUrl) {
        PropiedadesNotificaciones propiedades = new PropiedadesNotificaciones(
                "resend+log", Duration.ofMinutes(2),
                new PropiedadesNotificaciones.Resend("clave-de-prueba", "no-reply@uni.edu", baseUrl));
        return new CorreoTransaccionalResendConLog(propiedades);
    }

    private static CorreoTransaccional.MensajeCorreo mensaje() {
        return new CorreoTransaccional.MensajeCorreo(
                "ana@uni.edu", "Confirma tu cuenta", "Tu token es abc123");
    }

    @Test
    @DisplayName("Envía por Resend y deja el token en el log")
    void enviaYRegistra(CapturedOutput salida) throws IOException {
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger peticiones = new AtomicInteger();
        String url = levantarStub(200, cuerpo, peticiones);

        adaptador(url).enviar(mensaje());

        assertThat(peticiones.get()).isEqualTo(1);
        assertThat(cuerpo.get()).contains("\"to\":[\"ana@uni.edu\"]").contains("Tu token es abc123");
        assertThat(salida.getOut())
                .contains("destino=ana@uni.edu")
                .contains("texto=Tu token es abc123");
    }

    @Test
    @DisplayName("Si Resend falla, el token YA quedó en el log y el fallo se propaga para el reintento")
    void registraAunqueFalleElProveedor(CapturedOutput salida) throws IOException {
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger peticiones = new AtomicInteger();
        String url = levantarStub(500, cuerpo, peticiones);

        assertThatThrownBy(() -> adaptador(url).enviar(mensaje()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");

        assertThat(peticiones.get()).isEqualTo(1);
        assertThat(salida.getOut()).as("el log va primero").contains("texto=Tu token es abc123");
    }
}
