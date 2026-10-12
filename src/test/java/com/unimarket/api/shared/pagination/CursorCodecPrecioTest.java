package com.unimarket.api.shared.pagination;

import com.unimarket.api.shared.error.EstructuraInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Variante de precio del cursor compuesto (D4): usada por {@code orden=precio} del catálogo.
 * Cubre roundtrip, malformados y el rechazo cruzado entre variantes (fecha vs precio).
 */
@DisplayName("CursorCodec — variante de precio")
class CursorCodecPrecioTest {

    private final CursorCodec codec = new CursorCodec(new ObjectMapper());

    @Test
    @DisplayName("Roundtrip: preserva el precio como decimal plano y el id")
    void roundtripPrecio() {
        UUID id = UUID.randomUUID();
        String token = codec.codificarPrecio(new BigDecimal("1234.50"), id);

        RegistroCursorPrecio estado = codec.decodificarPrecio(token);

        assertThat(estado.precio()).isEqualByComparingTo(new BigDecimal("1234.50"));
        assertThat(estado.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("El token es opaco: no contiene el decimal en claro")
    void tokenOpaco() {
        String token = codec.codificarPrecio(new BigDecimal("10.00"), UUID.randomUUID());

        assertThat(token).doesNotContain("10.00");
    }

    @Test
    @DisplayName("Malformados → 400 estructural")
    void malformados() {
        assertThatThrownBy(() -> codec.decodificarPrecio("no-es-base64!!"))
                .isInstanceOf(EstructuraInvalidaException.class);
        assertThatThrownBy(() -> codec.decodificarPrecio(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes())))
                .isInstanceOf(EstructuraInvalidaException.class);
        assertThatThrownBy(() -> codec.decodificarPrecio(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"p\":\"abc\",\"i\":\"no-uuid\"}".getBytes())))
                .isInstanceOf(EstructuraInvalidaException.class);
        assertThatThrownBy(() -> codec.decodificarPrecio(null))
                .isInstanceOf(EstructuraInvalidaException.class);
    }

    @Test
    @DisplayName("Rechazo cruzado: un cursor de fecha no decodifica como precio y viceversa")
    void rechazoCruzado() {
        String deFecha = codec.codificar(new RegistroCursor(Instant.now(), UUID.randomUUID()));
        String dePrecio = codec.codificarPrecio(new BigDecimal("5.00"), UUID.randomUUID());

        assertThatThrownBy(() -> codec.decodificarPrecio(deFecha)).isInstanceOf(EstructuraInvalidaException.class);
        assertThatThrownBy(() -> codec.decodificar(dePrecio)).isInstanceOf(EstructuraInvalidaException.class);
    }
}
