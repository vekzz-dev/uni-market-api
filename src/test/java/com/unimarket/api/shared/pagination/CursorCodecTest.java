package com.unimarket.api.shared.pagination;

import com.unimarket.api.shared.error.EstructuraInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursor compuesto {@code (fechaCreacion, id)} en base64url (D4): roundtrip con precisión
 * de microsegundos y rechazo estricto de entradas ilegibles (400 estructural).
 */
@DisplayName("CursorCodec")
class CursorCodecTest {

    private static final UUID ID = UUID.fromString("6b0b0d1e-9a3c-4f2e-8b7a-1c2d3e4f5a6b");
    private static final Instant FECHA = Instant.parse("2026-10-10T12:34:56.123456Z");

    private final CursorCodec codec = new CursorCodec(JsonMapper.builder().build());

    @Test
    @DisplayName("Roundtrip: preserva el instante (micros) y el UUID")
    void decodificar_shouldPreserveInstantAndUuid_whenCursorIsValid() {
        RegistroCursor original = new RegistroCursor(FECHA, ID);

        RegistroCursor recuperado = codec.decodificar(codec.codificar(original));

        assertThat(recuperado).isEqualTo(original);
    }

    @Test
    @DisplayName("Codifica base64url sin padding de un JSON compacto {\"f\":micros,\"i\":uuid}")
    void codificar_shouldProduceUrlSafeBase64WithoutPadding() {
        String cursor = codec.codificar(new RegistroCursor(Instant.EPOCH, ID));

        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8))
                .isEqualTo("{\"f\":0,\"i\":\"" + ID + "\"}");
    }

    @ParameterizedTest(name = "cursor ilegible: {0}")
    @MethodSource("cursoresMalformados")
    @DisplayName("Cursor malformado o no decodificable → EstructuraInvalidaException (400)")
    void decodificar_shouldThrowEstructuraInvalida_whenCursorIsMalformed(String cursor) {
        assertThatThrownBy(() -> codec.decodificar(cursor))
                .isInstanceOf(EstructuraInvalidaException.class);
    }

    private static Stream<String> cursoresMalformados() {
        return Stream.of(
                "no-base64",
                "####",
                "",
                base64Url("{}"),
                base64Url("[1,2]"),
                base64Url("{\"f\":1700000000000000,\"i\":\"no-es-un-uuid\"}"),
                base64Url("{\"f\":\"1700000000000000\",\"i\":\"" + ID + "\"}"));
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
