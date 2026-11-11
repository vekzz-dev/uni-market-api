package com.unimarket.api.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selección del adaptador de correo por configuración (D12). Importa porque un valor
 * literal en `application-dev.yml` gana sobre el placeholder del base y deja la variable
 * de entorno inservible en silencio: el perfil dev decía `log` fijo y `NOTIFICACIONES_PROVEEDOR`
 * no tenía efecto. Aquí se fija qué bean aparece para cada modo.
 */
@DisplayName("Configuración del correo por proveedor")
class ConfiguracionCorreoTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConfiguracionNotificaciones.class,
                    CorreoTransaccionalLog.class,
                    CorreoTransaccionalResend.class,
                    CorreoTransaccionalResendConLog.class)
            .withPropertyValues(
                    "app.notificaciones.resend.api-key=clave-de-prueba",
                    "app.notificaciones.resend.remitente=no-reply@uni.edu");

    @Test
    @DisplayName("Sin configuración arranca el adaptador de log (default de dev)")
    void porDefectoEsLog() {
        runner.run(contexto -> {
            assertThat(contexto).hasSingleBean(CorreoTransaccional.class);
            assertThat(contexto.getBean(CorreoTransaccional.class)).isInstanceOf(CorreoTransaccionalLog.class);
        });
    }

    @Test
    @DisplayName("proveedor=log arranca solo el adaptador de log")
    void logExplicito() {
        runner.withPropertyValues("app.notificaciones.proveedor=log")
                .run(contexto -> {
                    assertThat(contexto).hasSingleBean(CorreoTransaccional.class);
                    assertThat(contexto.getBean(CorreoTransaccional.class)).isInstanceOf(CorreoTransaccionalLog.class);
                });
    }

    @Test
    @DisplayName("proveedor=resend arranca solo el adaptador de Resend")
    void resendSolo() {
        runner.withPropertyValues("app.notificaciones.proveedor=resend")
                .run(contexto -> {
                    assertThat(contexto).hasSingleBean(CorreoTransaccional.class);
                    assertThat(contexto.getBean(CorreoTransaccional.class)).isInstanceOf(CorreoTransaccionalResend.class);
                });
    }

    @Test
    @DisplayName("proveedor=resend+log arranca el compuesto: envía y registra")
    void resendConLog() {
        runner.withPropertyValues("app.notificaciones.proveedor=resend+log")
                .run(contexto -> {
                    assertThat(contexto).hasSingleBean(CorreoTransaccional.class);
                    assertThat(contexto.getBean(CorreoTransaccional.class))
                            .isInstanceOf(CorreoTransaccionalResendConLog.class);
                });
    }

    @Test
    @DisplayName("proveedor=resend+log sin credenciales falla al arrancar con mensaje accionable")
    void resendConLogSinCredenciales() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfiguracionNotificaciones.class, CorreoTransaccionalResendConLog.class)
                .withPropertyValues("app.notificaciones.proveedor=resend+log")
                .run(contexto -> assertThat(contexto).hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("RESEND_API_KEY"));
    }
}
