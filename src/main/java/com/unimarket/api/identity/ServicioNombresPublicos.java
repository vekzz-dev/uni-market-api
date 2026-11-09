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

    /** Genérico neutro para una contraparte de mensajería (R18): no insinúa un rol. */
    public static final String SIN_NOMBRE_USUARIO = NombresPublicos.SIN_NOMBRE_USUARIO;

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
        return nombrePublicoDe(usuarioId, SIN_NOMBRE);
    }

    /** Variante con genérico propio, para vistas donde el rol del otro no está fijado (R18). */
    @Transactional(readOnly = true)
    public String nombrePublicoDe(UUID usuarioId, String generico) {
        if (usuarioId == null) {
            return generico;
        }
        return usuarios.findById(usuarioId)
                .filter(Usuario::activa)
                .map(usuario -> NombresPublicos.publico(usuario.getNombre()))
                .orElse(generico);
    }

    /**
     * Nombres públicos de un conjunto de vendedores en UNA consulta.
     *
     * @return un nombre por cada id pedido: nunca falta una entrada (los no resolubles
     *         quedan en {@link NombresPublicos#SIN_NOMBRE}), así el mapeador no repite la regla
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> nombresPublicosDe(Collection<UUID> vendedorIds) {
        return nombresPublicosDe(vendedorIds, SIN_NOMBRE);
    }

    /** Variante con genérico propio: una entrada por id pedido, también para los no resolubles. */
    @Transactional(readOnly = true)
    public Map<UUID, String> nombresPublicosDe(Collection<UUID> vendedorIds, String generico) {
        LinkedHashSet<UUID> distintos = new LinkedHashSet<>();
        if (vendedorIds != null) {
            vendedorIds.stream().filter(Objects::nonNull).forEach(distintos::add);
        }
        if (distintos.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> nombres = new LinkedHashMap<>();
        distintos.forEach(id -> nombres.put(id, generico));
        usuarios.findAllById(distintos).stream()
                .filter(Usuario::activa)
                .forEach(usuario -> nombres.put(usuario.getId(), NombresPublicos.publico(usuario.getNombre())));
        return Map.copyOf(nombres);
    }
}
