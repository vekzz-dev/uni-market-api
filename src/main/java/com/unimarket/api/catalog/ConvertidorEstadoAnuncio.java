package com.unimarket.api.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Dominio cerrado de {@link EstadoAnuncio} respaldado por el CHECK de la BD (DBDD §3). */
@Converter
public class ConvertidorEstadoAnuncio implements AttributeConverter<EstadoAnuncio, String> {

    @Override
    public String convertToDatabaseColumn(EstadoAnuncio estado) {
        return estado == null ? null : estado.name();
    }

    @Override
    public EstadoAnuncio convertToEntityAttribute(String valor) {
        return valor == null ? null : EstadoAnuncio.valueOf(valor);
    }
}
