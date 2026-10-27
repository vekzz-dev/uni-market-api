package com.unimarket.api.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Dominio cerrado de {@link OrigenRechazo} respaldado por el CHECK de la BD (DBDD §3.2). */
@Converter
public class ConvertidorOrigenRechazo implements AttributeConverter<OrigenRechazo, String> {

    @Override
    public String convertToDatabaseColumn(OrigenRechazo origen) {
        return origen == null ? null : origen.name();
    }

    @Override
    public OrigenRechazo convertToEntityAttribute(String valor) {
        return valor == null ? null : OrigenRechazo.valueOf(valor);
    }
}
