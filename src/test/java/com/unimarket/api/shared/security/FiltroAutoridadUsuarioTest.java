package com.unimarket.api.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Autoridad por request (D3, R3/R14/R15): el rol se deriva de la fila del usuario, no del
 * claim, y el usuario ausente, inhabilitado o dado de baja corta la cadena con el 401
 * genérico en {@code application/problem+json}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FiltroAutoridadUsuario")
class FiltroAutoridadUsuarioTest {

    @Mock
    private EstadoUsuarioProvider proveedor;

    private final ManejadoresProblemaSeguridad manejadores = new ManejadoresProblemaSeguridad();

    private FiltroAutoridadUsuario filtro;
    private MockHttpServletRequest solicitud;
    private MockHttpServletResponse respuesta;
    private MockFilterChain cadena;

    @BeforeEach
    void preparar() {
        filtro = new FiltroAutoridadUsuario(proveedor, manejadores.puntoEntradaProblema());
        solicitud = new MockHttpServletRequest("GET", "/v1/anuncios");
        respuesta = new MockHttpServletResponse();
        cadena = new MockFilterChain();
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Usuario vigente: continúa la cadena con ROLE_<ROL> de la BD (claim sustituido)")
    void doFilter_shouldContinueChainWithDatabaseRole_whenUserIsActive() throws Exception {
        UUID id = UUID.randomUUID();
        when(proveedor.porId(id)).thenReturn(Optional.of(new EstadoUsuario(id, Rol.moderador, true, false)));
        autenticarComoJwt(id.toString());

        filtro.doFilter(solicitud, respuesta, cadena);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MODERADOR");
        assertThat(cadena.getRequest()).isSameAs(solicitud);
    }

    @Test
    @DisplayName("Usuario inexistente en la BD: 401 problem+json y corta la cadena")
    void doFilter_shouldRespondProblem401_whenUserIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(proveedor.porId(id)).thenReturn(Optional.empty());
        autenticarComoJwt(id.toString());

        filtro.doFilter(solicitud, respuesta, cadena);

        assertarRechazo401();
    }

    @Test
    @DisplayName("Usuario inhabilitado (habilitado=false): 401 problem+json y corta la cadena")
    void doFilter_shouldRespondProblem401_whenUserIsDisabled() throws Exception {
        UUID id = UUID.randomUUID();
        when(proveedor.porId(id)).thenReturn(Optional.of(new EstadoUsuario(id, Rol.miembro, false, false)));
        autenticarComoJwt(id.toString());

        filtro.doFilter(solicitud, respuesta, cadena);

        assertarRechazo401();
    }

    @Test
    @DisplayName("Usuario dado de baja (eliminado=true): 401 problem+json y corta la cadena")
    void doFilter_shouldRespondProblem401_whenUserIsDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        when(proveedor.porId(id)).thenReturn(Optional.of(new EstadoUsuario(id, Rol.miembro, true, true)));
        autenticarComoJwt(id.toString());

        filtro.doFilter(solicitud, respuesta, cadena);

        assertarRechazo401();
    }

    @Test
    @DisplayName("`sub` que no es UUID: 401 genérico sin consultar la BD")
    void doFilter_shouldRespondProblem401_whenSubjectIsNotUuid() throws Exception {
        autenticarComoJwt("no-es-un-uuid");

        filtro.doFilter(solicitud, respuesta, cadena);

        assertarRechazo401();
    }

    @Test
    @DisplayName("Autenticación sin principal Jwt: no consulta la BD y continúa la cadena")
    void doFilter_shouldContinueChainWithoutLookup_whenAuthenticationIsNotJwt() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "clave-anonima", "anónimo", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        filtro.doFilter(solicitud, respuesta, cadena);

        assertThat(cadena.getRequest()).isSameAs(solicitud);
        verifyNoInteractions(proveedor);
    }

    private static void autenticarComoJwt(String sujeto) {
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "HS256")
                .subject(sujeto)
                .claim("roles", List.of("miembro"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MIEMBRO"))));
    }

    private void assertarRechazo401() throws Exception {
        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(respuesta.getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(respuesta.getContentAsString())
                .contains("\"status\":401")
                .contains("No autenticado");
        assertThat(cadena.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MIEMBRO");
    }
}
