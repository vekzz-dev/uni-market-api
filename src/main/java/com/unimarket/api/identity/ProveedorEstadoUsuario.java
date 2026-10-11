package com.unimarket.api.identity;

import com.unimarket.api.shared.security.EstadoUsuario;
import com.unimarket.api.shared.security.EstadoUsuarioProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementación del puerto de autoridad por request (D3): resuelve rol, habilitación y
 * baja vigentes desde la fila de {@code usuarios} en cada petición autenticada. El cambio
 * de rol surte efecto en la siguiente petición sin esperar la expiración del JWT (R3/R14)
 * y la baja o inhabilitación cortan la sesión (R15).
 */
@Component
public class ProveedorEstadoUsuario implements EstadoUsuarioProvider {

    private final UsuarioRepository usuarios;

    public ProveedorEstadoUsuario(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    public Optional<EstadoUsuario> porId(UUID id) {
        return usuarios.findById(id)
                .map(usuario -> new EstadoUsuario(
                        usuario.getId(), usuario.getRol(), usuario.isHabilitado(), !usuario.activa()));
    }
}
