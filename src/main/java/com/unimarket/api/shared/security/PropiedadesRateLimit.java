package com.unimarket.api.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración externalizada del límite de tasa (Q1/R13, N3): intentos permitidos por
 * minuto y propósito. El almacenamiento sigue en memoria — correcto para el despliegue
 * único documentado; un despliegue multi-instancia exigiría un backing compartido.
 */
@ConfigurationProperties("app.seguridad.rate-limit")
public record PropiedadesRateLimit(Integer intentosPorMinuto) {

    /** Intentos efectivos: 5/min si no hay valor válido configurado (Q1). */
    public int intentosOPorDefecto() {
        return intentosPorMinuto == null || intentosPorMinuto < 1 ? 5 : intentosPorMinuto;
    }
}
