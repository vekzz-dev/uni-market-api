package com.unimarket.api.catalog;

import com.unimarket.api.media.AlmacenObjetos;
import com.unimarket.api.media.PropiedadesMedia;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de negocio del ciclo de vida del anuncio (R5–R8) con dobles: validaciones de
 * imágenes por firma, precio y compensación de storage ante fallo de la transacción.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicioAnuncios")
class ServicioAnunciosTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

    @Mock
    private AnuncioRepository repositorio;

    @Mock
    private AlmacenObjetos almacen;

    @Mock
    private com.unimarket.api.identity.ServicioNombresPublicos nombres;

    private PropiedadesMedia medios;
    private ServicioAnuncios servicio;

    @BeforeEach
    void preparar() {
        medios = new PropiedadesMedia(
                "/tmp/uni-market-test", "filesystem", 2097152, List.of("image/jpeg", "image/png", "image/gif", "image/webp"),
                5, 5242880, List.of("image/jpeg", "image/png", "image/gif", "image/webp"), null);
        when(nombres.nombrePublicoDe(any())).thenReturn("Ana P.");
        servicio = new ServicioAnuncios(repositorio, almacen, medios, new MapeadorAnuncios(nombres));
    }

    private static MultipartFile imagenJpeg() {
        return new MockMultipartFile("imagenes", "foto.jpg", "image/jpeg", JPEG);
    }

    @Nested
    @DisplayName("Creación")
    class Creacion {

        @Test
        @DisplayName("Sube las imágenes validadas y nace pendiente con arrays pareados")
        void crearFeliz() {
            when(almacen.guardar(anyString(), anyString(), any()))
                    .thenAnswer(invocacion -> AlmacenObjetos.urlDesdeRuta(invocacion.getArgument(0)));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));

            UUID vendedor = UUID.randomUUID();
            DtosAnuncios.AnuncioPanel creado = servicio.crear(
                    vendedor, "Bici de ruta", new BigDecimal("150.00"), "Como nueva", 4, List.of(imagenJpeg()));

            assertThat(creado.estadoRevision()).isEqualTo(EstadoRevision.pendiente);
            assertThat(creado.imagenUrls()).hasSize(1).allMatch(url -> url.startsWith("/media/anuncios/" + vendedor));
            verify(repositorio).saveAndFlush(any(Anuncio.class));
        }

        @Test
        @DisplayName("Compensación: si la transacción falla, los objetos subidos se eliminan")
        void compensaAnteFallo() {
            List<String> subidas = new ArrayList<>();
            when(almacen.guardar(anyString(), anyString(), any())).thenAnswer(invocacion -> {
                String ruta = invocacion.getArgument(0);
                subidas.add(ruta);
                return AlmacenObjetos.urlDesdeRuta(ruta);
            });
            when(repositorio.saveAndFlush(any())).thenThrow(new IllegalStateException("fallo simulado de BD"));

            assertThatThrownBy(() -> servicio.crear(
                    UUID.randomUUID(), "Bici", new BigDecimal("10.00"), null, null, List.of(imagenJpeg(), imagenJpeg())))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(subidas).hasSize(2);
            subidas.forEach(ruta -> verify(almacen).eliminar(ruta));
        }

        @Test
        @DisplayName("Precio fuera de rango → 422 sin subir nada")
        void precioFueraDeRango() {
            assertThatThrownBy(() -> servicio.crear(
                    UUID.randomUUID(), "Bici", new BigDecimal("-1"), null, null, List.of(imagenJpeg())))
                    .isInstanceOf(ReglaDeNegocioException.class);
            assertThatThrownBy(() -> servicio.crear(
                    UUID.randomUUID(), "Bici", new BigDecimal("100000000.00"), null, null, List.of(imagenJpeg())))
                    .isInstanceOf(ReglaDeNegocioException.class);
            verify(almacen, never()).guardar(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Más de 5 imágenes → 422 sin subir nada")
        void demasiadasImagenes() {
            List<MultipartFile> seis = List.of(imagenJpeg(), imagenJpeg(), imagenJpeg(), imagenJpeg(), imagenJpeg(), imagenJpeg());

            assertThatThrownBy(() -> servicio.crear(UUID.randomUUID(), "Bici", new BigDecimal("10"), null, null, seis))
                    .isInstanceOf(ReglaDeNegocioException.class);
            verify(almacen, never()).guardar(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Contenido que no es imagen (firma inválida) → 422 sin subir nada")
        void firmaInvalida() {
            MultipartFile falso = new MockMultipartFile("imagenes", "x.jpg", "image/jpeg", "no soy una imagen".getBytes());

            assertThatThrownBy(() -> servicio.crear(
                    UUID.randomUUID(), "Bici", new BigDecimal("10"), null, null, List.of(falso)))
                    .isInstanceOf(ReglaDeNegocioException.class);
            verify(almacen, never()).guardar(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Imagen por encima del máximo → 422 sin subir nada")
        void demasiadoGrande() {
            byte[] grandes = new byte[(int) medios.imagenMaxBytes() + 1];
            System.arraycopy(JPEG, 0, grandes, 0, JPEG.length);
            MultipartFile pesada = new MockMultipartFile("imagenes", "g.jpg", "image/jpeg", grandes);

            assertThatThrownBy(() -> servicio.crear(
                    UUID.randomUUID(), "Bici", new BigDecimal("10"), null, null, List.of(pesada)))
                    .isInstanceOf(ReglaDeNegocioException.class);
            verify(almacen, never()).guardar(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Edición y ownership (R6/R7)")
    class Edicion {

        private Anuncio conTrail(UUID vendedor, EstadoRevision revision) {
            Anuncio a = new Anuncio();
            a.setVendedorId(vendedor);
            a.setNombre("Original");
            a.setPrecio(new BigDecimal("10.00"));
            a.setEstadoRevision(revision);
            a.setRevisadoPor(UUID.randomUUID());
            a.setFechaRevision(java.time.Instant.now());
            a.setMotivoRechazo("motivo previo");
            return a;
        }

        @Test
        @DisplayName("Editar un aprobado lo devuelve a pendiente y limpia el trail (R6)")
        void edicionReseteaRevision() {
            UUID vendedor = UUID.randomUUID();
            Anuncio anuncio = conTrail(vendedor, EstadoRevision.aprobado);
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));

            DtosAnuncios.AnuncioPanel editado = servicio.editar(
                    vendedor, UUID.randomUUID(), new DtosAnuncios.ActualizarRequest("Nuevo nombre", null, null, null));

            assertThat(editado.estadoRevision()).isEqualTo(EstadoRevision.pendiente);
            assertThat(editado.motivoRechazo()).isNull();
            assertThat(editado.fechaRevision()).isNull();
        }

        @Test
        @DisplayName("Editar un pendiente lo deja pendiente (sin trail que limpiar)")
        void edicionDePendienteNoCambiaRevision() {
            UUID vendedor = UUID.randomUUID();
            Anuncio anuncio = conTrail(vendedor, EstadoRevision.pendiente);
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));

            DtosAnuncios.AnuncioPanel editado = servicio.editar(
                    vendedor, UUID.randomUUID(), new DtosAnuncios.ActualizarRequest(null, new BigDecimal("20.00"), null, null));

            assertThat(editado.estadoRevision()).isEqualTo(EstadoRevision.pendiente);
            assertThat(editado.precio()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("Anuncio de otro vendedor → 403; inexistente → 404 (R7)")
        void guardsDeOwnership() {
            Anuncio anuncio = conTrail(UUID.randomUUID(), EstadoRevision.pendiente);
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));

            assertThatThrownBy(() -> servicio.editar(
                    UUID.randomUUID(), UUID.randomUUID(), new DtosAnuncios.ActualizarRequest("x", null, null, null)))
                    .isInstanceOf(ProhibidoException.class);

            when(repositorio.findById(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> servicio.marcarVendido(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(NoEncontradoException.class);
        }
    }

    @Nested
    @DisplayName("Reemplazo de imágenes (N6)")
    class ReemplazoDeImagenes {

        private Anuncio conImagenes(UUID vendedor, EstadoRevision revision, String... rutas) {
            Anuncio a = new Anuncio();
            a.setVendedorId(vendedor);
            a.setNombre("Item");
            a.setPrecio(new BigDecimal("10.00"));
            a.setEstadoRevision(revision);
            a.setImagenesUrls(java.util.Arrays.stream(rutas).map(AlmacenObjetos::urlDesdeRuta).toList());
            a.setImagenesPaths(List.of(rutas));
            if (revision != EstadoRevision.pendiente) {
                a.setRevisadoPor(UUID.randomUUID());
                a.setFechaRevision(java.time.Instant.now());
            }
            return a;
        }

        @Test
        @DisplayName("Sustituye los arrays, elimina las viejas y resetea la revisión (R6/R8)")
        void reemplazoFeliz() {
            UUID vendedor = UUID.randomUUID();
            Anuncio anuncio = conImagenes(vendedor, EstadoRevision.aprobado, "anuncios/" + vendedor + "/vieja.jpg");
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
            when(almacen.guardar(anyString(), anyString(), any()))
                    .thenAnswer(invocacion -> AlmacenObjetos.urlDesdeRuta(invocacion.getArgument(0)));

            DtosAnuncios.AnuncioPanel actualizado =
                    servicio.reemplazarImagenes(vendedor, UUID.randomUUID(), List.of(imagenJpeg()));

            assertThat(actualizado.imagenUrls()).hasSize(1);
            assertThat(actualizado.estadoRevision()).isEqualTo(EstadoRevision.pendiente);
            verify(almacen).eliminar("anuncios/" + vendedor + "/vieja.jpg");
        }

        @Test
        @DisplayName("Compensación: si la tx falla, las nuevas se borran y las viejas se conservan")
        void compensaYSinTocarViejas() {
            UUID vendedor = UUID.randomUUID();
            Anuncio anuncio = conImagenes(vendedor, EstadoRevision.pendiente, "anuncios/" + vendedor + "/vieja.jpg");
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));
            when(almacen.guardar(anyString(), anyString(), any()))
                    .thenAnswer(invocacion -> AlmacenObjetos.urlDesdeRuta(invocacion.getArgument(0)));
            when(repositorio.saveAndFlush(any())).thenThrow(new IllegalStateException("fallo simulado"));

            assertThatThrownBy(() -> servicio.reemplazarImagenes(vendedor, UUID.randomUUID(), List.of(imagenJpeg())))
                    .isInstanceOf(IllegalStateException.class);

            // La vieja nunca se toca; la nueva subida sí se compensa (rollback descarta el resto).
            verify(almacen, never()).eliminar("anuncios/" + vendedor + "/vieja.jpg");
            verify(almacen).eliminar(anyString());
        }

        @Test
        @DisplayName("Anuncio ajeno → 403 (R7)")
        void ajenoFuera() {
            Anuncio anuncio = conImagenes(UUID.randomUUID(), EstadoRevision.pendiente, "anuncios/x/vieja.jpg");
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));

            assertThatThrownBy(() -> servicio.reemplazarImagenes(UUID.randomUUID(), UUID.randomUUID(), List.of(imagenJpeg())))
                    .isInstanceOf(ProhibidoException.class);
            verify(almacen, never()).guardar(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Visibilidad, vendido y baja")
    class CicloDeVida {

        private Anuncio de(UUID vendedor, EstadoRevision revision, EstadoAnuncio estado) {
            Anuncio a = new Anuncio();
            a.setVendedorId(vendedor);
            a.setNombre("Item");
            a.setPrecio(new BigDecimal("10.00"));
            a.setEstadoRevision(revision);
            a.setEstado(estado);
            return a;
        }

        @Test
        @DisplayName("Visibilidad sobre no aprobado → 422; sobre aprobado alterna disponible↔inactivo")
        void visibilidad() {
            UUID vendedor = UUID.randomUUID();
            Anuncio pendiente = de(vendedor, EstadoRevision.pendiente, EstadoAnuncio.disponible);
            when(repositorio.findById(any())).thenReturn(Optional.of(pendiente));
            assertThatThrownBy(() -> servicio.alternarVisibilidad(vendedor, UUID.randomUUID(), false))
                    .isInstanceOf(ReglaDeNegocioException.class);

            Anuncio aprobado = de(vendedor, EstadoRevision.aprobado, EstadoAnuncio.disponible);
            when(repositorio.findById(any())).thenReturn(Optional.of(aprobado));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
            assertThat(servicio.alternarVisibilidad(vendedor, UUID.randomUUID(), false).estado())
                    .isEqualTo(EstadoAnuncio.inactivo);
        }

        @Test
        @DisplayName("Vendido desde disponible; repetirlo → 422")
        void vendido() {
            UUID vendedor = UUID.randomUUID();
            Anuncio anuncio = de(vendedor, EstadoRevision.aprobado, EstadoAnuncio.disponible);
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));

            assertThat(servicio.marcarVendido(vendedor, UUID.randomUUID()).estado()).isEqualTo(EstadoAnuncio.vendido);
            assertThatThrownBy(() -> servicio.marcarVendido(vendedor, UUID.randomUUID()))
                    .isInstanceOf(ReglaDeNegocioException.class);
        }

        @Test
        @DisplayName("Baja = estado eliminado, sin tocar los objetos de imagen (R1)")
        void bajaLogica() {
            UUID vendedor = UUID.randomUUID();
            Anuncio anuncio = de(vendedor, EstadoRevision.aprobado, EstadoAnuncio.disponible);
            when(repositorio.findById(any())).thenReturn(Optional.of(anuncio));
            when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));

            servicio.darDeBaja(vendedor, UUID.randomUUID());

            assertThat(anuncio.getEstado()).isEqualTo(EstadoAnuncio.eliminado);
            verify(almacen, never()).eliminar(anyString());
            verify(repositorio, times(1)).saveAndFlush(anuncio);
        }
    }
}
