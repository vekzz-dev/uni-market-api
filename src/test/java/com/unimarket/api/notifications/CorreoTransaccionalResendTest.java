package com.unimarket.api.notifications;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptador Resend (D12) contra un stub HTTP local: forma de la petición (endpoint,
 * bearer, JSON with/from/to) y manejo del fallo del proveedor. Sin red externa.
 */
@DisplayName("CorreoTransaccionalResend")
class CorreoTransaccionalResendTest {

    private HttpServer servidor;

    @AfterEach
    void apagar() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private String levantarStub(int codigo, AtomicReference<String> autorizacion, AtomicReference<String> cuerpo) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(0), 0);
        servidor.createContext("/emails", intercambio -> {
            autorizacion.set(intercambio.getRequestHeaders().getFirst("Authorization"));
            cuerpo.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(codigo, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        servidor.start();
        return "http://localhost:" + servidor.getAddress().getPort() + "/emails";
    }

    private CorreoTransaccionalResend adaptador(String baseUrl) {
        PropiedadesNotificaciones propiedades = new PropiedadesNotificaciones(
                "resend", Duration.ofMinutes(2),
                new PropiedadesNotificaciones.Resend("clave-de-prueba", "no-reply@uni.edu", baseUrl));
        return new CorreoTransaccionalResend(HttpClient.newHttpClient(), propiedades);
    }

    @Test
    @DisplayName("Envía POST con bearer y JSON correctos")
    void envioCorrecto() throws IOException {
        AtomicReference<String> autorizacion = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        String url = levantarStub(200, autorizacion, cuerpo);

        adaptador(url).enviar(new CorreoTransaccional.MensajeCorreo(
                "ana@uni.edu", "Confirma tu cuenta", "Tu token es abc123"));

        assertThat(autorizacion.get()).isEqualTo("Bearer clave-de-prueba");
        assertThat(cuerpo.get())
                .contains("\"from\":\"no-reply@uni.edu\"")
                .contains("\"to\":[\"ana@uni.edu\"]")
                .contains("\"subject\":\"Confirma tu cuenta\"")
                .contains("\"text\":\"Tu token es abc123\"");
    }

    @Test
    @DisplayName("El escape JSON neutraliza comillas y control")
    void escapeJson() throws IOException {
        AtomicReference<String> autorizacion = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        String url = levantarStub(200, autorizacion, cuerpo);

        adaptador(url).enviar(new CorreoTransaccional.MensajeCorreo(
                "ana@uni.edu", "Asunto \"raro\"", "línea1\nlínea2"));

        assertThat(cuerpo.get())
                .contains("Asunto \\\"raro\\\"")
                .contains("línea1\\nlínea2");
    }

    @Test
    @DisplayName("Respuesta no-200 del proveedor → excepción con el código, sin reintentos aquí")
    void falloDelProveedor() throws IOException {
        AtomicReference<String> autorizacion = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        String url = levantarStub(500, autorizacion, cuerpo);

        CorreoTransaccional.MensajeCorreo mensaje = new CorreoTransaccional.MensajeCorreo(
                "ana@uni.edu", "Asunto", "Texto");

        assertThatThrownBy(() -> adaptador(url).enviar(mensaje))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }
}
