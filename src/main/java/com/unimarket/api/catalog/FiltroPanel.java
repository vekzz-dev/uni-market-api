package com.unimarket.api.catalog;

import org.springframework.data.jpa.domain.Specification;

/**
 * Filtro del panel del vendedor (issues #3 y #4). Son cuatro chips excluyentes, pero solo
 * tres son estados de revisión: {@code retraido} es la <em>vista</em> de un rechazo cuya
 * procedencia es {@link OrigenRechazo#retraccion}, y {@code rechazado} es la del rechazo
 * de revisión. Mantener ambas dimensiones separadas evita ensuciar el enum publicado
 * {@link EstadoRevision}, que los clientes generados ya consumen.
 */
public enum FiltroPanel {

    pendiente,
    aprobado,
    rechazado,
    retraido;

    /** Predicado de repositorio del chip: el filtro se aplica en la consulta, nunca en memoria. */
    Specification<Anuncio> especificacion() {
        return switch (this) {
            case pendiente -> EspecificacionesAnuncio.deRevision(EstadoRevision.pendiente);
            case aprobado -> EspecificacionesAnuncio.deRevision(EstadoRevision.aprobado);
            case rechazado -> EspecificacionesAnuncio.deRevision(EstadoRevision.rechazado)
                    .and(EspecificacionesAnuncio.deOrigenRechazo(OrigenRechazo.rechazo));
            case retraido -> EspecificacionesAnuncio.deRevision(EstadoRevision.rechazado)
                    .and(EspecificacionesAnuncio.deOrigenRechazo(OrigenRechazo.retraccion));
        };
    }
}
