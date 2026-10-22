package com.unimarket.api.catalog;

import com.unimarket.api.identity.ServicioNombresPublicos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Costura catálogo → identity del nombre público (R17, issue #2): la página resuelve sus
 * vendedores en UNA llamada (sin N+1) y el detalle/panel resuelven uno solo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MapeadorAnuncios")
class MapeadorAnunciosTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID LUIS = UUID.randomUUID();

    @Mock
    private ServicioNombresPublicos nombres;

    @InjectMocks
    private MapeadorAnuncios mapeador;

    private Anuncio anuncio(UUID vendedorId) {
        Anuncio anuncio = new Anuncio();
        anuncio.setVendedorId(vendedorId);
        anuncio.setNombre("Calculadora");
        anuncio.setPrecio(new BigDecimal("45.00"));
        return anuncio;
    }

    @Test
    @DisplayName("La página resuelve todos sus vendedores con una sola llamada y repite el nombre por vendedor")
    void paginaResuelveEnLote() {
        when(nombres.nombresPublicosDe(any())).thenReturn(Map.of(ANA, "Ana P.", LUIS, "Luis G."));
        List<Anuncio> pagina = List.of(anuncio(ANA), anuncio(LUIS), anuncio(ANA));

        List<DtosAnuncios.AnuncioResumen> resumenes = mapeador.resumenes(pagina);

        assertThat(resumenes).extracting(DtosAnuncios.AnuncioResumen::vendedorNombre)
                .containsExactly("Ana P.", "Luis G.", "Ana P.");
        verify(nombres, times(1)).nombresPublicosDe(any());
        verifyNoMoreInteractions(nombres);
    }

    @Test
    @DisplayName("El detalle resuelve un solo vendedor")
    void detalleResuelveUno() {
        when(nombres.nombrePublicoDe(ANA)).thenReturn("Ana P.");

        DtosAnuncios.AnuncioDetalle detalle = mapeador.detalle(anuncio(ANA));

        assertThat(detalle.vendedorNombre()).isEqualTo("Ana P.");
        verify(nombres, times(1)).nombrePublicoDe(ANA);
        verifyNoMoreInteractions(nombres);
    }

    @Test
    @DisplayName("El panel publica el mismo nombre público heredado del resumen")
    void panelHeredaElNombre() {
        when(nombres.nombrePublicoDe(LUIS)).thenReturn("Luis G.");

        DtosAnuncios.AnuncioPanel panel = mapeador.panel(anuncio(LUIS));

        assertThat(panel.vendedorNombre()).isEqualTo("Luis G.");
    }
}
