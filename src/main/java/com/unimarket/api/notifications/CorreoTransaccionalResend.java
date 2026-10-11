package com.unimarket.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Adaptador Resend para prod (D12): email vía API HTTP con {@code HttpClient} del JDK
 * (sin SDK oficial Java ni dependencias extra). Se activa tras
 * {@code app.notificaciones.proveedor=resend} con {@code RESEND_API_KEY} configurada.
 */
@Service
@ConditionalOnProperty(name = "app.notificaciones.proveedor", havingValue = "resend")
public class CorreoTransaccionalResend implements CorreoTransaccional {

    private static final URI API = URI.create("https://api.resend.com/emails");

    private final HttpClient http;
    private final String apiKey;
    private final String remitente;

    public CorreoTransaccionalResend(PropiedadesNotificaciones propiedades) {
        this(HttpClient.newHttpClient(), propiedades);
    }

    CorreoTransaccionalResend(HttpClient http, PropiedadesNotificaciones propiedades) {
        if (propiedades.resend() == null
                || propiedades.resend().apiKey() == null || propiedades.resend().apiKey().isBlank()
                || propiedades.resend().remitente() == null || propiedades.resend().remitente().isBlank()) {
            throw new IllegalStateException(
                    "Proveedor resend sin configurar: define RESEND_API_KEY y RESEND_REMITENTE.");
        }
        this.http = http;
        this.apiKey = propiedades.resend().apiKey();
        this.remitente = propiedades.resend().remitente();
    }

    @Override
    public void enviar(MensajeCorreo mensaje) {
        String cuerpo = """
                {"from":%s,"to":[%s],"subject":%s,"text":%s}""".formatted(
                json(remitente), json(mensaje.destino()), json(mensaje.asunto()), json(mensaje.texto()));
        HttpRequest peticion;
        try {
            peticion = HttpRequest.newBuilder(API)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                throw new IllegalStateException("Resend respondió " + respuesta.statusCode());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo enviar el correo vía Resend", e);
        }
    }

    /** Escape mínimo JSON para cadenas (comillas, barra invertida y control). */
    private static String json(String texto) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
