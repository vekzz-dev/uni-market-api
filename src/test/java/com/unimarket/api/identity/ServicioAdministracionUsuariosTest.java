package com.unimarket.api.identity;

import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.security.Rol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Reglas del panel de usuarios (R14/R15) con dobles: dominio del rol destino, sin
 * auto-promoción, cuentas dadas de baja invisibles para el panel, habilitación reversible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicioAdministracionUsuarios")
class ServicioAdministracionUsuariosTest {

    @Mock
    private UsuarioRepository usuarios;

    private ServicioAdministracionUsuarios servicio;

    @BeforeEach
    void preparar() {
        when(usuarios.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        servicio = new ServicioAdministracionUsuarios(usuarios);
    }

    private Usuario usuario(String email, Rol rol) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setNombre("Usuario");
        u.setHashContrasena("hash");
        u.setRol(rol);
        return u;
    }

    @Nested
    @DisplayName("Cambio de rol (R14)")
    class CambioDeRol {

        @Test
        @DisplayName("Dominio cerrado: fuera de dominio o nulo → 422; acepta variantes de caso")
        void dominioDelRol() {
            assertThatThrownBy(() -> ServicioAdministracionUsuarios.parsearRol("superadmin"))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThatThrownBy(() -> ServicioAdministracionUsuarios.parsearRol(null))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThat(ServicioAdministracionUsuarios.parsearRol("  Moderador "))
                    .isEqualTo(Rol.moderador);
        }

        @Test
        @DisplayName("Auto-promoción → 403 (ni para el propio admin)")
        void sinAutoPromocion() {
            UUID actor = UUID.randomUUID();
            Usuario propio = usuario("admin@uni.edu", Rol.admin);
            when(usuarios.findById(any())).thenReturn(Optional.of(propio));

            assertThatThrownBy(() -> servicio.cambiarRol(actor, actor, "miembro"))
                    .isInstanceOf(ProhibidoException.class);
        }

        @Test
        @DisplayName("Rol igual al actual → 422 sin efectos")
        void rolIgual() {
            Usuario objetivo = usuario("objetivo@uni.edu", Rol.moderador);
            when(usuarios.findById(any())).thenReturn(Optional.of(objetivo));

            assertThatThrownBy(() -> servicio.cambiarRol(UUID.randomUUID(), UUID.randomUUID(), "moderador"))
                    .isInstanceOf(ReglaDeNegocioException.class);
        }

        @Test
        @DisplayName("Cambio válido persiste el rol nuevo")
        void cambioValido() {
            Usuario objetivo = usuario("objetivo@uni.edu", Rol.miembro);
            when(usuarios.findById(any())).thenReturn(Optional.of(objetivo));

            UsuarioAdminRespuesta respuesta = servicio.cambiarRol(UUID.randomUUID(), UUID.randomUUID(), "moderador");

            assertThat(respuesta.rol()).isEqualTo(Rol.moderador);
            assertThat(objetivo.getRol()).isEqualTo(Rol.moderador);
        }

        @Test
        @DisplayName("Cuenta dada de baja o inexistente → 404 para el panel (R15)")
        void bajasInvisibles() {
            Usuario dadoDeBaja = usuario("baja@uni.edu", Rol.miembro);
            dadoDeBaja.setFechaEliminacion(Instant.now());
            when(usuarios.findById(any())).thenReturn(Optional.of(dadoDeBaja));

            assertThatThrownBy(() -> servicio.cambiarHabilitacion(UUID.randomUUID(), true))
                    .as("una baja es invisible para el panel").isInstanceOf(NoEncontradoException.class);
            assertThatThrownBy(() -> servicio.cambiarRol(UUID.randomUUID(), UUID.randomUUID(), "moderador"))
                    .isInstanceOf(NoEncontradoException.class);

            when(usuarios.findById(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> servicio.cambiarHabilitacion(UUID.randomUUID(), true))
                    .isInstanceOf(NoEncontradoException.class);
        }
    }

    @Nested
    @DisplayName("Habilitación (R15)")
    class Habilitacion {

        @Test
        @DisplayName("Cambia la bandera y la devuelve en la respuesta")
        void cambiaBandera() {
            Usuario objetivo = usuario("toggle@uni.edu", Rol.miembro);
            when(usuarios.findById(any())).thenReturn(Optional.of(objetivo));

            UsuarioAdminRespuesta respuesta = servicio.cambiarHabilitacion(UUID.randomUUID(), false);

            assertThat(respuesta.habilitado()).isFalse();
            assertThat(objetivo.isHabilitado()).isFalse();
        }
    }
}
