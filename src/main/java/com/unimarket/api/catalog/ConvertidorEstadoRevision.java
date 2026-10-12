package com.unimarket.api.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Dominio cerrado de {@link EstadoRevision} respaldado por el CHECK de la BD (DBDD §3). */
@Converter
public class ConvertidorEstadoRevision implements AttributeConverter<EstadoRevision, String> {

    @Override
    public String convertToDatabaseColumn(EstadoRevision estado) {
        return estado == null ? null : estado.name();
    }

    @Override
    public EstadoRevision convertToEntityAttribute(String valor) {
        return valor == null ? null : EstadoRevision.valueOf(valor);
    }
}
