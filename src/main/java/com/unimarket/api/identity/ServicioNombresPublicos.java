package com.unimarket.api.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Servicio de aplicación expuesto a otros módulos para publicar el nombre del vendedor
 * (R17): el catálogo nunca lee {@code usuarios} directamente y nunca ve el nombre completo,
 * solo el resultado de {@link NombresPublicos}.
 *
 * <p>El listado resuelve M vendedores con UNA sola consulta ({@code findAllById}): el
 * catálogo pagina N anuncios y resolver cada vendedor por separado introduciría N+1. Las
 * cuentas dadas de baja no se resuelven (R1: toda lectura filtra {@code fechaEliminacion})
 * y caen al genérico, igual que una cuenta inexistente.
 */
@Service
public class ServicioNombresPublicos {

    /**
     * Genérico publicado cuando el nombre no es resoluble. Forma parte del contrato del
     * módulo: los consumidores no necesitan (ni deben) importar la clase de regla.
     */
    public static final String SIN_NOMBRE = NombresPublicos.SIN_NOMBRE;

    private final UsuarioRepository usuarios;

    public ServicioNombresPublicos(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /**
     * Nombre público de un vendedor para el detalle y el panel.
     *
     * @return el nombre público, o {@link NombresPublicos#SIN_NOMBRE} si no es resoluble
     */
    @Transactional(readOnly = true)
    public String nombrePublicoDe(UUID usuarioId) {
        if (usuarioId == null) {
            return SIN_NOMBRE;
        }
        return usuarios.findById(usuarioId)
                .filter(Usuario::activa)
                .map(usuario -> NombresPublicos.publico(usuario.getNombre()))
                .orElse(SIN_NOMBRE);
    }

    /**
     * Nombres públicos de un conjunto de vendedores en UNA consulta.
     *
     * @return un nombre por cada id pedido: nunca falta una entrada (los no resolubles
     *         quedan en {@link NombresPublicos#SIN_NOMBRE}), así el mapeador no repite la regla
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> nombresPublicosDe(Collection<UUID> vendedorIds) {
        LinkedHashSet<UUID> distintos = new LinkedHashSet<>();
        if (vendedorIds != null) {
            vendedorIds.stream().filter(Objects::nonNull).forEach(distintos::add);
        }
        if (distintos.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> nombres = new LinkedHashMap<>();
        distintos.forEach(id -> nombres.put(id, SIN_NOMBRE));
        usuarios.findAllById(distintos).stream()
                .filter(Usuario::activa)
                .forEach(usuario -> nombres.put(usuario.getId(), NombresPublicos.publico(usuario.getNombre())));
        return Map.copyOf(nombres);
    }
}
