package com.unimarket.api.identity;

import com.unimarket.api.shared.security.PropiedadesRateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Límite de tasa configurable (Q1/R13, N3): el número de intentos llega de configuración
 * y la ventana separa claves por propósito, IP y email.
 */
@DisplayName("LimitadorTasa")
class LimitadorTasaTest {

    @Test
    @DisplayName("Con límite 1, el segundo intento de la misma clave → 429")
    void limiteConfigurable() {
        LimitadorTasa limitador = new LimitadorTasa(new PropiedadesRateLimit(1));

        assertThatCode(() -> limitador.exigir("login", "10.0.0.1", "Ana@Uni.edu")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limitador.exigir("login", "10.0.0.1", "ana@uni.edu"))
                .as("el email se normaliza para la clave").isInstanceOf(DemasiadasPeticionesException.class);
    }

    @Test
    @DisplayName("Sin configuración válida el default es 5; otros propósitos tienen su propia ventana")
    void defaultYCero() {
        LimitadorTasa porDefecto = new LimitadorTasa(new PropiedadesRateLimit(null));
        LimitadorTasa ceroInvalido = new LimitadorTasa(new PropiedadesRateLimit(0));

        for (int intento = 0; intento < 5; intento++) {
            int numero = intento;
            assertThatCode(() -> porDefecto.exigir("reset", "10.0.0.2", "x@uni.edu"))
                    .as("intento %d dentro del límite", numero).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> porDefecto.exigir("reset", "10.0.0.2", "x@uni.edu"))
                .isInstanceOf(DemasiadasPeticionesException.class);
        assertThatCode(() -> porDefecto.exigir("reenvio", "10.0.0.2", "x@uni.edu"))
                .as("clave separada por propósito").doesNotThrowAnyException();

        assertThatCode(() -> ceroInvalido.exigir("login", "10.0.0.3", "y@uni.edu")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IP distinta no comparte ventana con la misma dirección de correo")
    void clavePorIp() {
        LimitadorTasa limitador = new LimitadorTasa(new PropiedadesRateLimit(1));
        limitador.exigir("login", "10.0.0.4", "z@uni.edu");

        assertThatCode(() -> limitador.exigir("login", "10.0.0.5", "z@uni.edu")).doesNotThrowAnyException();
    }
}
