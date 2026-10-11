package com.unimarket.api.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Autoridad por request (D3, R3/R14/R15): el JWT solo aporta la identidad ({@code sub});
 * el rol, la habilitación y la baja vigentes se resuelven contra la BD en cada request.
 *
 * <p>Si el usuario no existe, está inhabilitado o fue dado de baja, delega el 401 genérico
 * en el {@link AuthenticationEntryPoint} (indistinguible, R13) y corta la cadena. Con estado
 * vigente sustituye las autoridades del token por {@code ROLE_<ROL>} derivadas de la fila,
 * de modo que un cambio de rol surte efecto sin esperar la expiración del access token.
 */
public class FiltroAutoridadUsuario extends OncePerRequestFilter {

    private final EstadoUsuarioProvider proveedor;
    private final AuthenticationEntryPoint puntoEntrada;

    public FiltroAutoridadUsuario(EstadoUsuarioProvider proveedor, AuthenticationEntryPoint puntoEntrada) {
        this.proveedor = proveedor;
        this.puntoEntrada = puntoEntrada;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion != null && autenticacion.getPrincipal() instanceof Jwt jwt) {
            Optional<EstadoUsuario> estado = estadoVigente(jwt.getSubject());
            if (estado.isEmpty() || !estado.get().habilitado() || estado.get().eliminado()) {
                logger.debug("Request rechazado: el usuario autenticado no está vigente");
                puntoEntrada.commence(solicitud, respuesta, new InsufficientAuthenticationException("Sesión no vigente"));
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(autenticacionConRol(jwt, estado.get().rol()));
        }
        cadena.doFilter(solicitud, respuesta);
    }

    /** Si el usuario no existe o no está vigente, el resultado es vacío; un {@code sub} que no es UUID también. */
    private Optional<EstadoUsuario> estadoVigente(String sujeto) {
        try {
            return Optional.ofNullable(sujeto).map(UUID::fromString).flatMap(proveedor::porId);
        } catch (IllegalArgumentException excepcion) {
            return Optional.empty();
        }
    }

    private static Authentication autenticacionConRol(Jwt jwt, Rol rol) {
        GrantedAuthority autoridad = new SimpleGrantedAuthority("ROLE_" + rol.name().toUpperCase(Locale.ROOT));
        return new JwtAuthenticationToken(jwt, List.of(autoridad));
    }
}
