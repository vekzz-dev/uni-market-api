package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capa web de identity: validación estructural de DTOs (400) y cableado de controladores,
 * sin filtros de seguridad y con servicios mockeados (SDD §9, slice {@code @WebMvcTest}).
 */
@WebMvcTest({ControladorAuth.class, ControladorUsuarios.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(LimitadorTasa.class)
@DisplayName("Controladores de identidad: validación estructural")
class IdentidadWebSliceTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ServicioRegistro servicioRegistro;

    @MockitoBean
    private ServicioSesion servicioSesion;

    @MockitoBean
    private ServicioRecuperacion servicioRecuperacion;

    @MockitoBean
    private ServicioPerfil servicioPerfil;

    @Nested
    @DisplayName("POST /v1/usuarios")
    class Registro {

        @Test
        @DisplayName("Email con formato inválido → 400 problem+json")
        void registro_shouldResponder400_cuandoEmailInvalido() {
            // Act + Assert
            assertThat(mvc.post().uri("/v1/usuarios")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"no-es-email\",\"nombre\":\"Ana\",\"contrasena\":\"secreta123\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        @Test
        @DisplayName("Contraseña bajo la política (menos de 8) → 400 estructural")
        void registro_shouldResponder400_cuandoContrasenaCorta() {
            // Act + Assert
            assertThat(mvc.post().uri("/v1/usuarios")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ana@correo.com\",\"nombre\":\"Ana\",\"contrasena\":\"corta\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        @Test
        @DisplayName("Nombre vacío → 400 estructural")
        void registro_shouldResponder400_cuandoNombreVacio() {
            // Act + Assert
            assertThat(mvc.post().uri("/v1/usuarios")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ana@correo.com\",\"nombre\":\"  \",\"contrasena\":\"secreta123\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Nested
    @DisplayName("POST /v1/auth/login")
    class Login {

        @Test
        @DisplayName("Sin contraseña → 400 estructural")
        void login_shouldResponder400_cuandoFaltaContrasena() {
            // Act + Assert
            assertThat(mvc.post().uri("/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ana@correo.com\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }
}
