package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla del nombre público del vendedor (R17, issue #2): se publica nombre de pila +
 * inicial del último apellido y JAMÁS el nombre completo. Sin nombre resoluble se
 * responde el genérico {@code "Vendedor"} (R1: las lecturas no exponen cuentas dadas de baja).
 */
@DisplayName("NombresPublicos")
class NombresPublicosTest {

    @Test
    @DisplayName("Nombre y apellido → nombre de pila + inicial del apellido")
    void nombreYApellido() {
        assertThat(NombresPublicos.publico("Ana Pérez")).isEqualTo("Ana P.");
    }

    @Test
    @DisplayName("Varios nombres y apellidos compuestos → primera palabra + inicial del ÚLTIMO apellido")
    void variosNombresYApellidos() {
        assertThat(NombresPublicos.publico("Ana María Pérez Gómez")).isEqualTo("Ana G.");
    }

    @Test
    @DisplayName("Un solo token no inventa apellido: se publica tal cual")
    void unSoloToken() {
        assertThat(NombresPublicos.publico("Ana")).isEqualTo("Ana");
    }

    @Test
    @DisplayName("Espacios sobrantes y múltiples no cambian el resultado")
    void espaciosSobrantes() {
        assertThat(NombresPublicos.publico("  Ana \t Pérez  ")).isEqualTo("Ana P.");
    }

    @Test
    @DisplayName("La inicial se normaliza a mayúscula; el nombre de pila queda como lo escribió el usuario")
    void inicialEnMayuscula() {
        assertThat(NombresPublicos.publico("ana pérez")).isEqualTo("ana P.");
    }

    @Test
    @DisplayName("Apellido de una sola letra conserva la forma con punto")
    void apellidoDeUnaLetra() {
        assertThat(NombresPublicos.publico("Ana P")).isEqualTo("Ana P.");
    }

    @Test
    @DisplayName("Nombre nulo o en blanco → genérico de la plataforma")
    void sinNombre() {
        assertThat(NombresPublicos.publico(null)).isEqualTo(NombresPublicos.SIN_NOMBRE);
        assertThat(NombresPublicos.publico("   ")).isEqualTo(NombresPublicos.SIN_NOMBRE);
    }

    @Test
    @DisplayName("Nunca excede el maxLength 60 del contrato")
    void respetaElMaximoDelContrato() {
        String nombreLargo = "A".repeat(120) + " Pérez";

        String publico = NombresPublicos.publico(nombreLargo);

        assertThat(publico).hasSize(60);
    }

    @Test
    @DisplayName("Nunca publica el nombre completo: el apellido queda reducido a su inicial")
    void nuncaPublicaElNombreCompleto() {
        assertThat(NombresPublicos.publico("Ana Pérez")).doesNotContain("Pérez");
    }
}
