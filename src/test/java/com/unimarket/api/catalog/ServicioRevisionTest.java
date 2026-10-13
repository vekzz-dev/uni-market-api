package com.unimarket.api.catalog;

import com.unimarket.api.shared.error.NoEncontradoException;
import com.unimarket.api.shared.error.ProhibidoException;
import com.unimarket.api.shared.error.ReglaDeNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Máquina de estados y guards del servicio de revisión (R5): transiciones válidas,
 * motivo obligatorio, anti-auto-decisión y auditoría del trail. Unit plano con dobles.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicioRevision")
class ServicioRevisionTest {

    @Mock
    private AnuncioRepository repositorio;

    private ServicioRevision servicio;

    @BeforeEach
    void preparar() {
        servicio = new ServicioRevision(repositorio);
        when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    private Anuncio anuncio(UUID vendedor, EstadoRevision revision, String motivo) {
        Anuncio a = new Anuncio();
        a.setVendedorId(vendedor);
        a.setNombre("Item");
        a.setPrecio(new BigDecimal("10.00"));
        a.setEstadoRevision(revision);
        a.setMotivoRechazo(motivo);
        if (revision != EstadoRevision.pendiente) {
            a.setRevisadoPor(UUID.randomUUID());
            a.setFechaRevision(Instant.now());
        }
        return a;
    }

    @Nested
    @DisplayName("Transiciones válidas")
    class Transiciones {

        @Test
        @DisplayName("Aprobar pendiente: aprobado + trail completo y sin motivo (R5)")
        void aprobar() {
            UUID vendedor = UUID.randomUUID();
            UUID revisor = UUID.randomUUID();
            Anuncio pendiente = anuncio(vendedor, EstadoRevision.pendiente, null);
            when(repositorio.findById(any())).thenReturn(Optional.of(pendiente));

            DtosAnuncios.AnuncioPanel decidido = servicio.decidir(UUID.randomUUID(), revisor, Decision.aprobar, null);

            assertThat(decidido.estadoRevision()).isEqualTo(EstadoRevision.aprobado);
            assertThat(decidido.motivoRechazo()).isNull();
            assertThat(decidido.fechaRevision()).isNotNull();
            assertThat(pendiente.getRevisadoPor()).isEqualTo(revisor);
        }

        @Test
        @DisplayName("Rechazar pendiente con motivo: rechazado + motivo recortado")
        void rechazar() {
            UUID vendedor = UUID.randomUUID();
            when(repositorio.findById(any()))
                    .thenReturn(Optional.of(anuncio(vendedor, EstadoRevision.pendiente, null)));

            DtosAnuncios.AnuncioPanel decidido = servicio.decidir(
                    UUID.randomUUID(), UUID.randomUUID(), Decision.rechazar, "  incumple las normas  ");

            assertThat(decidido.estadoRevision()).isEqualTo(EstadoRevision.rechazado);
            assertThat(decidido.motivoRechazo()).isEqualTo("incumple las normas");
        }

        @Test
        @DisplayName("Retraer un aprobado con motivo: vuelve a rechazado con motivo nuevo")
        void retraer() {
            when(repositorio.findById(any()))
                    .thenReturn(Optional.of(anuncio(UUID.randomUUID(), EstadoRevision.aprobado, null)));

            DtosAnuncios.AnuncioPanel decidido = servicio.decidir(
                    UUID.randomUUID(), UUID.randomUUID(), Decision.retraer, "imagen inapropiada");

            assertThat(decidido.estadoRevision()).isEqualTo(EstadoRevision.rechazado);
            assertThat(decidido.motivoRechazo()).isEqualTo("imagen inapropiada");
        }

        @Test
        @DisplayName("Aprobar limpia un motivo previo")
        void aprobarLimpiaMotivo() {
            when(repositorio.findById(any()))
                    .thenReturn(Optional.of(anuncio(UUID.randomUUID(), EstadoRevision.pendiente, "motivo viejo")));

            assertThat(servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.aprobar, null).motivoRechazo())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Guards (R5)")
    class Guards {

        @Test
        @DisplayName("Auto-decisión → 403 incluso para admin; antes de validar el estado")
        void antiAuto() {
            UUID vendedorRevisor = UUID.randomUUID();
            when(repositorio.findById(any()))
                    .thenReturn(Optional.of(anuncio(vendedorRevisor, EstadoRevision.aprobado, null)));

            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), vendedorRevisor, Decision.aprobar, null))
                    .isInstanceOf(ProhibidoException.class);
        }

        @Test
        @DisplayName("Estados de origen inválidos → 422 (aprobar/rechazar solo pendiente; retraer solo aprobado)")
        void estadosInvalidos() {
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio(UUID.randomUUID(), EstadoRevision.aprobado, null)));
            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.aprobar, null))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.rechazar, "m"))
                    .isInstanceOf(ReglaDeNegocioException.class);

            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio(UUID.randomUUID(), EstadoRevision.pendiente, null)));
            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.retraer, "m"))
                    .isInstanceOf(ReglaDeNegocioException.class);
        }

        @Test
        @DisplayName("Motivo ausente o en blanco en rechazar/retraer → 422")
        void motivoObligatorio() {
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio(UUID.randomUUID(), EstadoRevision.pendiente, null)));
            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.rechazar, null))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.rechazar, "   "))
                    .isInstanceOf(ReglaDeNegocioException.class);
        }

        @Test
        @DisplayName("Anuncio inexistente → 404")
        void inexistente() {
            when(repositorio.findById(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> servicio.decidir(UUID.randomUUID(), UUID.randomUUID(), Decision.aprobar, null))
                    .isInstanceOf(NoEncontradoException.class);
        }
    }

    @Nested
    @DisplayName("Cola")
    class Cola {

        @Test
        @DisplayName("Mapea page a offset y usa el filtro por estado")
        void mapeoOffset() {
            Anuncio pendiente = anuncio(UUID.randomUUID(), EstadoRevision.pendiente, null);
            when(repositorio.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(pendiente), Pageable.ofSize(20), 1));

            var respuesta = servicio.cola(EstadoRevision.pendiente, 0, 20);

            assertThat(respuesta.datos()).hasSize(1);
            assertThat(respuesta.datos().get(0).estadoRevision()).isEqualTo(EstadoRevision.pendiente);
            assertThat(respuesta.paginacion()).isInstanceOf(com.unimarket.api.shared.pagination.PaginacionOffset.class);
        }
    }
}
