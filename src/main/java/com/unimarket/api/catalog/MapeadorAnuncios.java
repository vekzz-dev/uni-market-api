package com.unimarket.api.catalog;

import com.unimarket.api.identity.NombresPublicos;
import com.unimarket.api.identity.ServicioNombresPublicos;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ensambla los DTOs del anuncio con el nombre público del vendedor (R17, issue #2).
 *
 * <p>Único punto donde el catálogo pide nombres a {@code identity}: el listado resuelve
 * todos sus vendedores en UNA llamada (sin N+1) y el detalle/panel resuelven uno. Los
 * servicios no repiten esta lógica ni hablan con {@code UsuarioRepository} (SDD §4).
 */
@Component
class MapeadorAnuncios {

    private final ServicioNombresPublicos nombres;

    MapeadorAnuncios(ServicioNombresPublicos nombres) {
        this.nombres = nombres;
    }

    /** Una sola consulta para toda la página del catálogo. */
    List<DtosAnuncios.AnuncioResumen> resumenes(List<Anuncio> anuncios) {
        Map<UUID, String> porVendedor = nombres.nombresPublicosDe(
                anuncios.stream().map(Anuncio::getVendedorId).toList());
        return anuncios.stream()
                .map(anuncio -> DtosAnuncios.AnuncioResumen.de(anuncio, nombre(porVendedor, anuncio)))
                .toList();
    }

    DtosAnuncios.AnuncioDetalle detalle(Anuncio anuncio) {
        return DtosAnuncios.AnuncioDetalle.de(anuncio, nombres.nombrePublicoDe(anuncio.getVendedorId()));
    }

    DtosAnuncios.AnuncioPanel panel(Anuncio anuncio) {
        return DtosAnuncios.AnuncioPanel.de(anuncio, nombres.nombrePublicoDe(anuncio.getVendedorId()));
    }

    /** Igual que {@link #resumenes}, para la cola de moderación y "mis anuncios". */
    List<DtosAnuncios.AnuncioPanel> paneles(List<Anuncio> anuncios) {
        Map<UUID, String> porVendedor = nombres.nombresPublicosDe(
                anuncios.stream().map(Anuncio::getVendedorId).toList());
        return anuncios.stream()
                .map(anuncio -> DtosAnuncios.AnuncioPanel.de(anuncio, nombre(porVendedor, anuncio)))
                .toList();
    }

    /** El servicio garantiza una entrada por vendedor; el fallback evita un nulo en el wire. */
    private static String nombre(Map<UUID, String> porVendedor, Anuncio anuncio) {
        return porVendedor.getOrDefault(anuncio.getVendedorId(), NombresPublicos.SIN_NOMBRE);
    }
}
