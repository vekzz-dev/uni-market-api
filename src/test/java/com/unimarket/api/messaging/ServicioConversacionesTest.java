package com.unimarket.api.messaging;

import com.unimarket.api.catalog.DtosAnuncios;
import com.unimarket.api.catalog.EstadoAnuncio;
import com.unimarket.api.catalog.ServicioCatalogo;
import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import com.unimarket.api.shared.pagination.CursorCodec;
import com.unimarket.api.shared.security.EstadoUsuarioProvider;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de mensajería con dobles (R9/R10/R16): dedupe, apertura con visibilidad y
 * vendedor activo, envío con contraparte activa y guards de participación.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicioConversaciones")
class ServicioConversacionesTest {

    @Mock
    private ConversacionRepository conversaciones;

    @Mock
    private MensajeRepository mensajes;

    @Mock
    private ServicioCatalogo catalogo;

    @Mock
    private EstadoUsuarioProvider estadoUsuarios;

    private ServicioConversaciones servicio;

    @BeforeEach
    void preparar() {
        when(conversaciones.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        when(mensajes.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        servicio = new ServicioConversaciones(conversaciones, mensajes, catalogo, estadoUsuarios,
                new CursorCodec(new ObjectMapper()));
    }

    private DtosAnuncios.AnuncioDetalle anuncioVisible(UUID vendedorId) {
        return new DtosAnuncios.AnuncioDetalle(UUID.randomUUID(), "Item", new BigDecimal("10.00"), null, null,
                List.of(), vendedorId, "Ana P.", Instant.now(), EstadoAnuncio.disponible, Instant.now());
    }

    private void vendedorActivo(UUID vendedorId, boolean activo) {
        when(estadoUsuarios.porId(vendedorId))
                .thenReturn(Optional.of(new com.unimarket.api.shared.security.EstadoUsuario(
                        vendedorId, Rol.miembro, activo, false)));
    }

    @Nested
    @DisplayName("Apertura (R9/R16)")
    class Apertura {

        @Test
        @DisplayName("Crea 201 la primera vez y devuelve 200 (creada=false) al reutilizar el par")
        void crearYReutilizar() {
            UUID vendedor = UUID.randomUUID();
            UUID comprador = UUID.randomUUID();
            UUID anuncioId = UUID.randomUUID();
            when(catalogo.detalle(anuncioId)).thenReturn(anuncioVisible(vendedor));
            vendedorActivo(vendedor, true);
            when(conversaciones.findByAnuncioIdAndCompradorId(anuncioId, comprador)).thenReturn(Optional.empty());

            assertThat(servicio.abrir(comprador, anuncioId).creada()).isTrue();

            Conversacion existente = new Conversacion(anuncioId, comprador, vendedor);
            when(conversaciones.findByAnuncioIdAndCompradorId(anuncioId, comprador)).thenReturn(Optional.of(existente));
            assertThat(servicio.abrir(comprador, anuncioId).creada()).isFalse();
        }

        @Test
        @DisplayName("Auto-conversación → 422 (R9)")
        void autoConversacion() {
            UUID vendedor = UUID.randomUUID();
            UUID anuncioId = UUID.randomUUID();
            when(catalogo.detalle(anuncioId)).thenReturn(anuncioVisible(vendedor));

            assertThatThrownBy(() -> servicio.abrir(vendedor, anuncioId))
                    .isInstanceOf(ReglaDeNegocioException.class);
        }

        @Test
        @DisplayName("Vendedor inactivo → 404 indistinguible (R16)")
        void vendedorInactivo() {
            UUID vendedor = UUID.randomUUID();
            UUID anuncioId = UUID.randomUUID();
            when(catalogo.detalle(anuncioId)).thenReturn(anuncioVisible(vendedor));
            vendedorActivo(vendedor, false);

            assertThatThrownBy(() -> servicio.abrir(UUID.randomUUID(), anuncioId))
                    .isInstanceOf(NoEncontradoException.class);
        }

        @Test
        @DisplayName("Anuncio no visible: el 404 viene del propio catálogo (R4)")
        void anuncioNoVisible() {
            UUID anuncioId = UUID.randomUUID();
            when(catalogo.detalle(anuncioId)).thenThrow(new NoEncontradoException("no visible"));

            assertThatThrownBy(() -> servicio.abrir(UUID.randomUUID(), anuncioId))
                    .isInstanceOf(NoEncontradoException.class);
        }
    }

    @Nested
    @DisplayName("Envío y participación (R10/R16)")
    class Envio {

        private Conversacion conParticipantes(UUID comprador, UUID vendedor) {
            return new Conversacion(UUID.randomUUID(), comprador, vendedor);
        }

        @Test
        @DisplayName("Texto vacío o en blanco → 422 (R10)")
        void textoVacio() {
            UUID comprador = UUID.randomUUID();
            Conversacion conversacion = conParticipantes(comprador, UUID.randomUUID());
            when(conversaciones.findById(any())).thenReturn(Optional.of(conversacion));

            assertThatThrownBy(() -> servicio.enviar(UUID.randomUUID(), comprador, "   "))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThatThrownBy(() -> servicio.enviar(UUID.randomUUID(), comprador, null))
                    .isInstanceOf(ReglaDeNegocioException.class);
        }

        @Test
        @DisplayName("Contraparte inactiva → 422 (R16); activa → guarda y refresca la actividad")
        void contraparte() {
            UUID comprador = UUID.randomUUID();
            UUID vendedor = UUID.randomUUID();
            Conversacion conversacion = conParticipantes(comprador, vendedor);
            when(conversaciones.findById(any())).thenReturn(Optional.of(conversacion));

            vendedorActivo(vendedor, false);
            assertThatThrownBy(() -> servicio.enviar(UUID.randomUUID(), comprador, "hola"))
                    .isInstanceOf(ReglaDeNegocioException.class);

            vendedorActivo(vendedor, true);
            DtosMensajeria.MensajeRespuesta respuesta = servicio.enviar(UUID.randomUUID(), comprador, "  hola  ");
            assertThat(respuesta.texto()).isEqualTo("hola");
            verify(conversaciones, org.mockito.Mockito.atLeastOnce()).saveAndFlush(conversacion);
        }

        @Test
        @DisplayName("No participante → 403 en detalle, historial y marcado (R10)")
        void noParticipante() {
            Conversacion conversacion = conParticipantes(UUID.randomUUID(), UUID.randomUUID());
            when(conversaciones.findById(any())).thenReturn(Optional.of(conversacion));
            UUID intruso = UUID.randomUUID();

            assertThatThrownBy(() -> servicio.detalle(UUID.randomUUID(), intruso))
                    .isInstanceOf(ProhibidoException.class);
            assertThatThrownBy(() -> servicio.historial(UUID.randomUUID(), intruso, null, 20))
                    .isInstanceOf(ProhibidoException.class);
            assertThatThrownBy(() -> servicio.marcarLecturas(UUID.randomUUID(), intruso))
                    .isInstanceOf(ProhibidoException.class);
        }

        @Test
        @DisplayName("Conversación inexistente → 404")
        void inexistente() {
            when(conversaciones.findById(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> servicio.detalle(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(NoEncontradoException.class);
        }
    }
}
