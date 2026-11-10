package com.unimarket.api.shared.texto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patrón `LIKE` para búsquedas de texto libre (O3): el usuario busca literal, así que los
 * comodines `%`, `_` y el propio escape `\` se escapan, y el patrón se normaliza a minúsculas
 * para que la comparación sea case-insensitive sin depender de `ILIKE` del motor.
 */
@DisplayName("PatronesLike")
class PatronesLikeTest {

    @Test
    @DisplayName("Envuelve el término en % y lo normaliza a minúsculas")
    void contieneNormaliza() {
        assertThat(PatronesLike.contiene("  Ana MARÍA  ")).isEqualTo("%ana maría%");
    }

    @Test
    @DisplayName("El % escrito por el usuario es literal, no un comodín")
    void porcentajeLiteral() {
        assertThat(PatronesLike.contiene("100% real")).isEqualTo("%100\\% real%");
    }

    @Test
    @DisplayName("El guion bajo y la barra invertida también se escapan")
    void guionBajoYBarra() {
        assertThat(PatronesLike.contiene("a_b")).isEqualTo("%a\\_b%");
        assertThat(PatronesLike.contiene("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    @DisplayName("El carácter de escape es el declarado para el LIKE")
    void caracterDeEscape() {
        assertThat(PatronesLike.ESCAPE).isEqualTo('\\');
    }
}
