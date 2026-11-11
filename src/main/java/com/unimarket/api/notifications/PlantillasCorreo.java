package com.unimarket.api.notifications;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Plantillas HTML de los correos transaccionales (issue #8). Viven como recursos para que
 * el diseño no se mezcle con la lógica: el oyente solo aporta valores.
 *
 * <p>La sustitución es por marcadores {@code {{clave}}} y escapa el HTML de los valores
 * interpolados: el nombre lo escribe el usuario y no puede inyectar marcado en el correo.
 */
@Component
public class PlantillasCorreo {

    private static final String CONFIRMACION = "plantillas/confirmacion.html";
    private static final String RESTABLECIMIENTO = "plantillas/restablecimiento.html";

    private final String confirmacion;
    private final String restablecimiento;

    public PlantillasCorreo() {
        this(CONFIRMACION, RESTABLECIMIENTO);
    }

    PlantillasCorreo(String rutaConfirmacion, String rutaRestablecimiento) {
        this.confirmacion = leer(rutaConfirmacion);
        this.restablecimiento = leer(rutaRestablecimiento);
    }

    /** HTML de confirmación con el PIN y el nombre del usuario. */
    public String confirmacion(String nombre, String pin, String vigencia) {
        return render(confirmacion, Map.of("nombre", nombre, "pin", pin, "vigencia", vigencia));
    }

    /** HTML de restablecimiento con el PIN y el nombre del usuario. */
    public String restablecimiento(String nombre, String pin, String vigencia) {
        return render(restablecimiento, Map.of("nombre", nombre, "pin", pin, "vigencia", vigencia));
    }

    private static String render(String plantilla, Map<String, String> valores) {
        String resultado = plantilla;
        for (Map.Entry<String, String> valor : valores.entrySet()) {
            resultado = resultado.replace("{{" + valor.getKey() + "}}", escapar(valor.getValue()));
        }
        return resultado;
    }

    private static String leer(String ruta) {
        try (InputStream entrada = new ClassPathResource(ruta).getInputStream()) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("No se pudo leer la plantilla " + ruta, error);
        }
    }

    private static String escapar(String valor) {
        return valor == null ? "" : valor
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
