package com.unimarket.api.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guarda de producción (perfil {@code prod}): el arranque aborta con mensaje accionable
 * cuando falta cualquier credencial crítica — JWT, R2 o Resend según los adaptadores.
 */
@DisplayName("GuardiaProduccion")
class GuardiaProduccionTest {

    private static PropiedadesJwt jwt(String secreto) {
        return new PropiedadesJwt(secreto, Duration.ofMinutes(15));
    }

    private static GuardiaProduccion guardia(PropiedadesJwt jwt, String media, String correo,
                                             String r2Endpoint, String r2Bucket, String r2Key,
                                             String r2Secret, String r2Url,
                                             String resendKey, String resendRemitente) {
        return new GuardiaProduccion(jwt, media, r2Endpoint, r2Bucket, r2Key, r2Secret, r2Url,
                correo, resendKey, resendRemitente);
    }

    private static GuardiaProduccion completa() {
        return guardia(jwt("secreto-de-prueba-suficientemente-largo"),
                "r2", "resend",
                "https://cuenta.r2.cloudflarestorage.com", "bucket", "key", "secret", "https://pub.r2.dev",
                "re_123", "no-reply@uni.edu");
    }

    @Nested
    @DisplayName("Fail-fast")
    class FailFast {

        @Test
        @DisplayName("Configuración completa arranca sin error")
        void completa() {
            assertThatCode(GuardiaProduccionTest::completa).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("JWT sin secreto aborta con la variable a definir")
        void sinJwt() {
            assertThatThrownBy(() -> guardia(jwt(""), "r2", "resend",
                    "https://e", "b", "k", "s", "https://u", "re_1", "r@u.edu"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRETO");
        }

        @Test
        @DisplayName("R2 activo sin credenciales aborta nombrando la variable faltante")
        void r2Incompleto() {
            assertThatThrownBy(() -> guardia(jwt("suficiente-para-pruebas"), "r2", "log",
                    "https://e", "b", "k", "  ", "https://u", "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MEDIA_R2_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("Resend activo sin API key aborta; con proveedor log no exige claves de correo")
        void resendIncompleto() {
            assertThatThrownBy(() -> guardia(jwt("suficiente-para-pruebas"), "filesystem", "resend",
                    "", "", "", "", "", "  ", "r@u.edu"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RESEND_API_KEY");

            assertThatCode(() -> guardia(jwt("suficiente-para-pruebas"), "filesystem", "log",
                    "", "", "", "", "", "", ""))
                    .doesNotThrowAnyException();
        }
    }
}
