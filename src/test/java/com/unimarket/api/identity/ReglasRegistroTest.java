package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalización y política del registro a nivel de servicio (R2/R11): sin Spring.
 */
@DisplayName("Reglas de registro (normalización y política)")
class ReglasRegistroTest {

    @Test
    @DisplayName("El email se recorta y minimiza: 'A@x.com' y 'a@x.com' son la misma cuenta (R2)")
    void normalizarEmail_shouldRecortarYMinimizar() {
        // Act + Assert
        assertThat(ServicioRegistro.normalizarEmail("  Ana@Correo.COM  ")).isEqualTo("ana@correo.com");
    }

    @Test
    @DisplayName("SHA-256: 64 hex Kraus, determinista y distinto por valor (R11)")
    void sha256Hex_shouldSerDeterministaYUnico() {
        // Act
        String primero = TokensSeguridad.sha256Hex("crudo-1");
        String segundo = TokensSeguridad.sha256Hex("crudo-1");
        String otro = TokensSeguridad.sha256Hex("crudo-2");

        // Assert
        assertThat(primero).hasSize(64).matches("[0-9a-f]+");
        assertThat(segundo).isEqualTo(primero);
        assertThat(otro).isNotEqualTo(primero);
    }

    @Test
    @DisplayName("El token crudo es opaco, único y apto para query (?token=)")
    void generarCrudo_shouldSerUnicoYAptoParaUrl() {
        // Act
        String primero = TokensSeguridad.generarCrudo();
        String segundo = TokensSeguridad.generarCrudo();

        // Assert
        assertThat(primero).isNotEqualTo(segundo);
        assertThat(primero).matches("[A-Za-z0-9_-]+");
    }
}
