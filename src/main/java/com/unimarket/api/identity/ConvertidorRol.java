package com.unimarket.api.identity;

import com.unimarket.api.shared.security.Rol;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Mapea el rol del dominio cerrado (R3: {@code miembro/moderador/admin}) a su texto en la
 * columna {@code usuarios.rol}. El CHECK {@code usuarios_rol_check} respalda el dominio en
 * la BD; cualquier otro valor viola la restricción.
 */
@Converter(autoApply = true)
public class ConvertidorRol implements AttributeConverter<Rol, String> {

    @Override
    public String convertToDatabaseColumn(Rol rol) {
        return rol == null ? null : rol.name();
    }

    @Override
    public Rol convertToEntityAttribute(String texto) {
        return texto == null ? null : Rol.valueOf(texto);
    }
}
