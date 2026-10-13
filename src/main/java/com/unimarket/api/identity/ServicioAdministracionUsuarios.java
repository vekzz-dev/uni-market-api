package com.unimarket.api.identity;

import com.unimarket.api.shared.error.EstructuraInvalidaException;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.pagination.PaginacionOffset;
import com.unimarket.api.shared.pagination.RespuestaColeccion;
import com.unimarket.api.shared.security.Rol;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Gestión de usuarios del panel (R14/R15, SDD §4): listado SOLO de cuentas activas
 * (las dadas de baja no existen para el panel), cambio de rol sin auto-promoción
 * ({@code 403} el propio, {@code 422} fuera de dominio o igual al actual) y
 * habilitación reversible. NO existe ningún camino que escriba {@code fechaEliminacion}:
 * la baja pertenece solo al flujo del propio usuario (R15).
 */
@Service
public class ServicioAdministracionUsuarios {

    static final int LIMITE_DEFECTO = 20;
    static final int LIMITE_MAXIMO = 100;

    private final UsuarioRepository usuarios;

    public ServicioAdministracionUsuarios(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Listado filtrable por rol; excluye dadas de baja (R15) y ordena por alta reciente. */
    @Transactional(readOnly = true)
    public RespuestaColeccion<UsuarioAdminRespuesta> listar(Rol rol, Integer pagina, Integer limite) {
        int tamano = validarLimite(limite);
        int indice = pagina == null || pagina < 0 ? 0 : pagina;
        Specification<Usuario> filtro = (raiz, consulta, cb) -> cb.isNull(raiz.get("fechaEliminacion"));
        if (rol != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.equal(raiz.get("rol"), rol));
        }
        Page<Usuario> resultado = usuarios.findAll(filtro,
                PageRequest.of(indice, tamano, Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"))));
        return new RespuestaColeccion<>(
                resultado.getContent().stream().map(UsuarioAdminRespuesta::de).toList(),
                new PaginacionOffset(indice, tamano, resultado.getTotalElements(), resultado.getTotalPages()));
    }

    /**
     * Cambia el rol de un usuario (R14): rol destino fuera de dominio o igual al actual
     * → {@code 422}; el PROPIO rol → {@code 403}; el efecto es inmediato (D3).
     */
    @Transactional
    public UsuarioAdminRespuesta cambiarRol(UUID actorId, UUID usuarioId, String rolCrudo) {
        Rol destino = parsearRol(rolCrudo);
        Usuario usuario = activoPorId(usuarioId);
        if (actorId.equals(usuarioId)) {
            throw new ProhibidoException("No puedes cambiar tu propio rol.");
        }
        if (usuario.getRol() == destino) {
            throw new ReglaDeNegocioException("El usuario ya tiene ese rol.");
        }
        usuario.setRol(destino);
        return UsuarioAdminRespuesta.de(usuarios.saveAndFlush(usuario));
    }

    /** Habilitación reversible (R15): inhabilitar corta login, refresh y lecturas (401 indistinguible). */
    @Transactional
    public UsuarioAdminRespuesta cambiarHabilitacion(UUID usuarioId, boolean habilitado) {
        Usuario usuario = activoPorId(usuarioId);
        usuario.setHabilitado(habilitado);
        return UsuarioAdminRespuesta.de(usuarios.saveAndFlush(usuario));
    }

    /** El panel solo ve y opera cuentas activas: una baja es 404 para el admin (R15). */
    private Usuario activoPorId(UUID usuarioId) {
        return usuarios.findById(usuarioId)
                .filter(usuario -> usuario.getFechaEliminacion() == null)
                .orElseThrow(() -> new NoEncontradoException("El usuario no existe."));
    }

    /** Dominio cerrado de R14: cualquier otro valor es 422 (excepción declarada del contrato). */
    static Rol parsearRol(String rolCrudo) {
        if (rolCrudo == null) {
            throw new ReglaDeNegocioException("El rol es obligatorio y debe ser miembro, moderador o admin.");
        }
        return switch (rolCrudo.strip().toLowerCase(Locale.ROOT)) {
            case "miembro" -> Rol.miembro;
            case "moderador" -> Rol.moderador;
            case "admin" -> Rol.admin;
            default -> throw new ReglaDeNegocioException("El rol debe ser miembro, moderador o admin.");
        };
    }

    private static int validarLimite(Integer limite) {
        if (limite == null) {
            return LIMITE_DEFECTO;
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new EstructuraInvalidaException("El parámetro 'limite' debe estar entre 1 y 100.");
        }
        return limite;
    }
}
