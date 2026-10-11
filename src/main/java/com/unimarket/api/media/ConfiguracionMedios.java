package com.unimarket.api.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra las propiedades de medios: única fuente de los límites de subida (R8, SDD §10).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropiedadesMedia.class)
public class ConfiguracionMedios {
}
