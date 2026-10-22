package com.unimarket.api.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Resolución de nombres públicos (R17): una sola consulta en lote para el catálogo,
 * cuentas dadas de baja o ausentes resueltas al genérico (R1/R15) y sin consultas
 * innecesarias cuando no hay vendedores que resolver.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServicioNombresPublicos")
class ServicioNombresPublicosTest {

    @Mock
    private UsuarioRepository usuarios;

    @InjectMocks
    private ServicioNombresPublicos servicio;

    private Usuario usuario(String nombre) {
        Usuario u = new Usuario();
        u.setNombre(nombre);
        return u;
    }

    @Test
    @DisplayName("El listado resuelve N vendedores con UNA consulta (sin N+1)")
    void resuelveEnLoteConUnaConsulta() {
        UUID ana = UUID.randomUUID();
        UUID luis = UUID.randomUUID();
        Usuario anaEntidad = usuario("Ana Pérez");
        anaEntidad.setId(ana);
        Usuario luisEntidad = usuario("Luis Gómez");
        luisEntidad.setId(luis);
        when(usuarios.findAllById(any())).thenReturn(List.of(anaEntidad, luisEntidad));

        Map<UUID, String> nombres = servicio.nombresPublicosDe(List.of(ana, luis, ana));

        assertThat(nombres).containsEntry(ana, "Ana P.").containsEntry(luis, "Luis G.");
        verify(usuarios, times(1)).findAllById(any());
        verifyNoMoreInteractions(usuarios);
    }

    @Test
    @DisplayName("Cuenta dada de baja o inexistente → genérico que no revela estado (R1/R15)")
    void cuentasNoResolublesUsanElGenerico() {
        UUID dadaDeBaja = UUID.randomUUID();
        UUID inexistente = UUID.randomUUID();
        Usuario baja = usuario("Ana Pérez");
        baja.setId(dadaDeBaja);
        baja.setFechaEliminacion(java.time.Instant.now());
        when(usuarios.findAllById(any())).thenReturn(List.of(baja));

        Map<UUID, String> nombres = servicio.nombresPublicosDe(List.of(dadaDeBaja, inexistente));

        assertThat(nombres)
                .containsEntry(dadaDeBaja, NombresPublicos.SIN_NOMBRE)
                .containsEntry(inexistente, NombresPublicos.SIN_NOMBRE);
    }

    @Test
    @DisplayName("Sin vendedores que resolver no consulta la base")
    void sinIdsNoConsulta() {
        assertThat(servicio.nombresPublicosDe(List.of())).isEmpty();

        verify(usuarios, never()).findAllById(any());
    }

    @Test
    @DisplayName("El detalle resuelve un solo vendedor")
    void resuelveUnoSolo() {
        UUID id = UUID.randomUUID();
        Usuario ana = usuario("Ana Pérez");
        ana.setId(id);
        when(usuarios.findById(id)).thenReturn(java.util.Optional.of(ana));

        assertThat(servicio.nombrePublicoDe(id)).isEqualTo("Ana P.");
    }
}
