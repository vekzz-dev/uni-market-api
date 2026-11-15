package com.unimarket.api.moderation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Dominio cerrado de {@link EstadoDenuncia} respaldado por el CHECK (DBDD §3.2). */
@Converter
public class ConvertidorEstadoDenuncia implements AttributeConverter<EstadoDenuncia, String> {

    @Override
    public String convertToDatabaseColumn(EstadoDenuncia estado) {
        return estado == null ? null : estado.name();
    }

    @Override
    public EstadoDenuncia convertToEntityAttribute(String valor) {
        return valor == null ? null : EstadoDenuncia.valueOf(valor);
    }
}
