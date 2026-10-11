package com.unimarket.api.notifications;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Cableado de notifications: propiedades externalizadas y envío asíncrono del oyente
 * post-commit (D8). El adaptador concreto lo elige
 * {@code app.notificaciones.proveedor=log|resend}.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(PropiedadesNotificaciones.class)
public class ConfiguracionNotificaciones {
}
