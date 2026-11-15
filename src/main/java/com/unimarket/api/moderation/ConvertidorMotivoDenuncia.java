package com.unimarket.api.moderation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Dominio cerrado de {@link MotivoDenuncia} respaldado por el CHECK (DBDD §3.2). */
@Converter
public class ConvertidorMotivoDenuncia implements AttributeConverter<MotivoDenuncia, String> {

    @Override
    public String convertToDatabaseColumn(MotivoDenuncia motivo) {
        return motivo == null ? null : motivo.name();
    }

    @Override
    public MotivoDenuncia convertToEntityAttribute(String valor) {
        return valor == null ? null : MotivoDenuncia.valueOf(valor);
    }
}
